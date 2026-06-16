package org.example.groceryguru.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geocoding service that converts street addresses to latitude/longitude coordinates.
 * <p>
 * Primary provider is OpenStreetMap Nominatim. Their usage policy REQUIRES at most one
 * request per second from a single source - this service enforces that globally using a
 * single-threaded executor + a minimum inter-request delay, regardless of how many callers
 * submit requests in parallel.
 * <p>
 * Query strategy:
 * <ol>
 *     <li>Structured query with street + city + postalcode</li>
 *     <li>Free-text query with cleaned street + city</li>
 *     <li>City + postal code only (last-resort, gives at least city-level coordinates)</li>
 * </ol>
 * Falls back to Google Maps Geocoding API if {@code google.maps.geocoding.api-key} is set.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private static final String NOMINATIM_BASE =
            "https://nominatim.openstreetmap.org/search?format=json&countrycodes=hr&limit=1";

    /** Nominatim usage policy: max 1 req / sec. We add 100 ms safety margin. */
    private static final long MIN_DELAY_MS = 1100L;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Single-threaded executor ensures only one Nominatim call happens at a time. */
    private final ExecutorService geocodingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "geocoding-worker");
        t.setDaemon(true);
        return t;
    });

    /** Shared across every call - timestamp of last Nominatim request, for global rate-limit. */
    private final AtomicLong lastRequestMs = new AtomicLong(0);

    @Value("${google.maps.geocoding.api-key:}")
    private String googleMapsApiKey;

    @PreDestroy
    public void shutdown() {
        geocodingExecutor.shutdown();
    }
    // Public API
    /**
     * Synchronously geocode an address. Blocks until the single-threaded queue processes
     * this request (subject to the global rate limit).
     * <p>
     * For bulk ingestion prefer {@link #geocodeAsync(String, String, int, BiConsumer)} so
     * the ingestion thread isn't blocked for many seconds per store.
     */
    public GeocodeResult geocode(String street, String city, int postalCode) {
        try {
            return geocodingExecutor.submit(() -> doGeocode(street, city, postalCode)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.debug("Geocoding failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Submits a geocode request to the background single-threaded worker. The callback
     * runs with the coordinates (or {@code null} on failure) when processing is done.
     * <p>
     * Callers can submit many requests back-to-back without blocking; the worker will
     * dispatch them one per second to respect the Nominatim policy.
     */
    public void geocodeAsync(String street, String city, int postalCode,
                             BiConsumer<GeocodeResult, Throwable> callback) {
        geocodingExecutor.submit(() -> {
            try {
                GeocodeResult result = doGeocode(street, city, postalCode);
                callback.accept(result, null);
            } catch (Throwable t) {
                callback.accept(null, t);
            }
        });
    }
    // Core logic (always runs on single-threaded executor)
    private GeocodeResult doGeocode(String rawStreet, String rawCity, int postalCode) {
        String street = cleanAddressComponent(rawStreet);
        String city = cleanAddressComponent(rawCity);

        if (city.isBlank() && street.isBlank()) return null;

        // 1. Structured query (most reliable for Croatian addresses)
        GeocodeResult result = nominatimStructured(street, city, postalCode);
        if (result != null) return result;

        // 2. Free-text fallback
        result = nominatimFreeText(street, city, postalCode);
        if (result != null) return result;

        // 3. City-only fallback - at worst we put the store in the right city
        if (!city.isBlank()) {
            result = nominatimStructured("", city, postalCode);
            if (result != null) {
                log.debug("City-only geocode for {} {} -> {},{}", city, postalCode,
                        result.latitude, result.longitude);
                return result;
            }
        }

        // 4. Google Maps as absolute last resort (if configured)
        if (googleMapsApiKey != null && !googleMapsApiKey.isBlank()) {
            result = geocodeWithGoogleMaps(street, city, postalCode);
            if (result != null) return result;
        }

        log.warn("Failed to geocode address: {} {} {}", street, city, postalCode);
        return null;
    }

    /**
     * Structured Nominatim request: sends {@code street=}, {@code city=}, {@code postalcode=}
     * as separate parameters. Much more reliable than free-text search for Croatian addresses
     * with compound street names.
     */
    private GeocodeResult nominatimStructured(String street, String city, int postalCode) {
        try {
            StringBuilder url = new StringBuilder(NOMINATIM_BASE);
            if (!street.isBlank()) {
                url.append("&street=").append(URLEncoder.encode(street, StandardCharsets.UTF_8));
            }
            if (!city.isBlank()) {
                url.append("&city=").append(URLEncoder.encode(city, StandardCharsets.UTF_8));
            }
            if (postalCode > 0) {
                url.append("&postalcode=").append(postalCode);
            }
            return callNominatim(url.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private GeocodeResult nominatimFreeText(String street, String city, int postalCode) {
        try {
            String query = (street.isBlank() ? "" : street + ", ") + city
                    + (postalCode > 0 ? " " + postalCode : "");
            String url = NOMINATIM_BASE + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            return callNominatim(url);
        } catch (Exception e) {
            return null;
        }
    }

    private GeocodeResult callNominatim(String url) {
        enforceRateLimit();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "GroceryGuru/1.0 (+https://groceryguru.app)")
                    .header("Accept-Language", "hr,en")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                return parseNominatimResponse(response.body());
            }
            if (response.statusCode() == 429) {
                log.warn("Nominatim rate-limited (429) - backing off 5s");
                Thread.sleep(5000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Nominatim HTTP error: {}", e.getMessage());
        }
        return null;
    }

    /** Sleeps if less than {@link #MIN_DELAY_MS} has elapsed since the previous call. */
    private void enforceRateLimit() {
        long now = System.currentTimeMillis();
        long last = lastRequestMs.get();
        long wait = MIN_DELAY_MS - (now - last);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestMs.set(System.currentTimeMillis());
    }

    private GeocodeResult parseNominatimResponse(String json) {
        if (json == null || json.isBlank() || json.trim().equals("[]")) return null;
        try {
            Pattern latPattern = Pattern.compile("\"lat\"\\s*:\\s*\"(-?[\\d.]+)\"");
            Pattern lonPattern = Pattern.compile("\"lon\"\\s*:\\s*\"(-?[\\d.]+)\"");
            Matcher latMatcher = latPattern.matcher(json);
            Matcher lonMatcher = lonPattern.matcher(json);
            if (latMatcher.find() && lonMatcher.find()) {
                double lat = Double.parseDouble(latMatcher.group(1));
                double lon = Double.parseDouble(lonMatcher.group(1));
                return new GeocodeResult(lat, lon);
            }
        } catch (Exception e) {
            log.debug("Failed to parse Nominatim response: {}", e.getMessage());
        }
        return null;
    }
    // Input cleaning
    /**
     * Strips Croatian quote marks, trailing junk, and normalizes whitespace so Nominatim
     * has a better chance of matching. Does NOT restore missing diacritics - Nominatim is
     * generally tolerant of those since it normalizes on its side.
     */
    private String cleanAddressComponent(String input) {
        if (input == null) return "";
        return input
                .replace('„', ' ').replace('"', ' ').replace('"', ' ').replace('"', ' ')
                .replace('»', ' ').replace('«', ' ')
                .replace('\u00A0', ' ')              // non-breaking space
                .replaceAll("\\s+", " ")
                .trim();
    }
    // Google Maps fallback (unchanged)
    private GeocodeResult geocodeWithGoogleMaps(String street, String city, int postalCode) {
        try {
            String query = street.isBlank() ? city : (street + " " + city);
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address=" + encoded
                    + "&components=country:HR&key=" + googleMapsApiKey;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return parseGoogleMapsResponse(response.body());
            }
        } catch (Exception e) {
            log.debug("Google Maps geocoding failed: {}", e.getMessage());
        }
        return null;
    }

    private GeocodeResult parseGoogleMapsResponse(String json) {
        try {
            Pattern latPattern = Pattern.compile("\"lat\"\\s*:\\s*([\\d.]+)");
            Pattern lngPattern = Pattern.compile("\"lng\"\\s*:\\s*([\\d.]+)");
            Matcher latMatcher = latPattern.matcher(json);
            Matcher lngMatcher = lngPattern.matcher(json);
            if (latMatcher.find() && lngMatcher.find()) {
                double lat = Double.parseDouble(latMatcher.group(1));
                double lon = Double.parseDouble(lngMatcher.group(1));
                return new GeocodeResult(lat, lon);
            }
        } catch (Exception e) {
            log.debug("Failed to parse Google Maps response: {}", e.getMessage());
        }
        return null;
    }

    public record GeocodeResult(Double latitude, Double longitude) {}
}
