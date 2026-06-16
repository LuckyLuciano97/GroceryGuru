package org.example.groceryguru.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KonzumIngestionStrategy implements StoreIngestionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KonzumIngestionStrategy.class);

    @Value("${ingestion.konzum.page-url:https://www.konzum.hr/cjenici}")
    private String pageUrl;

    @Value("${ingestion.konzum.max-pages:10}")
    private int maxPages;

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "href=\"(/cjenici/download\\?title=[^\"]+)\""
    );

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "(SUPERMARKET|HIPERMARKET),(.+?)\\s+(\\d{5})\\s+(.+?),(\\d{4}),(\\d+),.*\\.CSV"
    );

    @Override
    public String getChainName() {
        return "Konzum";
    }

    @Override
    public List<ParsedPrice> fetchAndParse() throws Exception {
        HttpClient client = createTrustingClient();
        List<ParsedPrice> allPrices = new ArrayList<>();

        // Step 1: Collect download links from ALL pages
        Set<String> downloadLinks = new LinkedHashSet<>();
        for (int page = 1; page <= maxPages; page++) {
            String url = pageUrl + "?page=" + page;
            log.info("Fetching Konzum page {}: {}", page, url);

            String html = fetchString(client, url);
            int before = downloadLinks.size();

            Matcher linkMatcher = LINK_PATTERN.matcher(html);
            while (linkMatcher.find()) {
                downloadLinks.add(linkMatcher.group(1));
            }

            int added = downloadLinks.size() - before;
            log.info("Page {}: found {} new links (total: {})", page, added, downloadLinks.size());

            if (added == 0) break; // no more pages
        }

        log.info("Total unique Konzum store CSVs to download: {}", downloadLinks.size());

        // Step 2: Download and parse each CSV
        int storeCount = 0;
        for (String link : downloadLinks) {
            try {
                String fullUrl = "https://www.konzum.hr" + link.replace("&amp;", "&");
                String decodedTitle = URLDecoder.decode(
                        link.replace("/cjenici/download?title=", ""),
                        StandardCharsets.UTF_8
                );

                StoreInfo storeInfo = parseStoreInfo(decodedTitle);
                if (storeInfo == null) {
                    log.debug("Could not parse store info from: {}", decodedTitle);
                    continue;
                }

                String csvContent = fetchString(client, fullUrl);
                List<ParsedPrice> storePrices = parseCsv(csvContent, storeInfo);
                allPrices.addAll(storePrices);
                storeCount++;

                if (storeCount % 50 == 0) {
                    log.info("Processed {}/{} Konzum stores ({} prices so far)",
                            storeCount, downloadLinks.size(), allPrices.size());
                }
            } catch (Exception e) {
                log.warn("Failed to process Konzum CSV: {}", e.getMessage());
            }
        }

        log.info("Finished Konzum ingestion: {} stores, {} total prices", storeCount, allPrices.size());
        return allPrices;
    }

    private StoreInfo parseStoreInfo(String title) {
        Matcher m = TITLE_PATTERN.matcher(title);
        if (!m.matches()) return null;

        int postalCode = Integer.parseInt(m.group(3));
        String city = m.group(4).trim();
        String storeCode = m.group(5);
        String street = m.group(2).trim(); // Address extracted from title

        return new StoreInfo("KONZUM_" + storeCode, "Konzum " + city, street, city, postalCode);
    }

    private List<ParsedPrice> parseCsv(String csvContent, StoreInfo store) {
        List<ParsedPrice> prices = new ArrayList<>();
        String[] lines = csvContent.split("\n");

        if (lines.length < 2) return prices;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isBlank()) continue;

            String[] fields = parseCsvLine(line);
            if (fields.length < 12) continue;

            try {
                String productName = fields[0].trim();
                String productCode = fields[1].trim();
                String brand = fields[2].trim();
                String netQuantity = fields[3].trim();
                String unitMeasure = fields[4].trim();
                BigDecimal retailPrice = parseDecimal(fields[5]);
                BigDecimal salePrice = parseDecimal(fields[7]);
                String barcode = fields[10].trim();
                String category = fields[11].trim();

                BigDecimal effectivePrice = salePrice != null ? salePrice : retailPrice;
                if (effectivePrice == null) continue;

                prices.add(new ParsedPrice(
                        productName, productCode, barcode, brand,
                        netQuantity, unitMeasure, category,
                        effectivePrice, salePrice,
                        store.code(), store.name(), store.street(), store.city(), store.postalCode()
                ));
            } catch (Exception e) {
                // skip malformed lines
            }
        }

        return prices;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields.toArray(new String[0]);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim().replace(",", ".");
        if (cleaned.startsWith(".")) cleaned = "0" + cleaned;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String fetchString(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept-Charset", "UTF-8")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }

        return EncodingUtil.detectAndDecode(response.body());
    }

    private HttpClient createTrustingClient() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(ctx)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private record StoreInfo(String code, String name, String street, String city, int postalCode) {}
}
