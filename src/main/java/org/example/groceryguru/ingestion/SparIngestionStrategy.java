package org.example.groceryguru.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SparIngestionStrategy implements StoreIngestionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SparIngestionStrategy.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${ingestion.spar.json-url:https://www.spar.hr/datoteke_cjenici/Cjenik%s.json}")
    private String jsonUrlTemplate;

    // Filename pattern: type_city_address_storeId_storeName_seq_date_time.csv
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
            "^(supermarket|hipermarket)_([^_]+(?:_[^_]+)*)_(\\d{3,5})_(.+?)_(\\d{4})_\\d{8}_\\d{4}\\.csv$"
    );

    @Override
    public String getChainName() {
        return "Spar";
    }

    @Override
    public List<ParsedPrice> fetchAndParse() throws Exception {
        HttpClient client = createTrustingClient();
        List<ParsedPrice> allPrices = new ArrayList<>();

        // Step 1: Fetch today's JSON index
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String jsonUrl = String.format(jsonUrlTemplate, today);
        log.info("Fetching Spar JSON index: {}", jsonUrl);

        String jsonContent = fetchString(client, jsonUrl);
        JsonNode root = mapper.readTree(jsonContent);
        JsonNode files = root.get("files");

        log.info("Found {} Spar CSV files", files.size());

        // Step 2: Download and parse each CSV
        int count = 0;
        for (JsonNode file : files) {
            String csvUrl = file.get("URL").asText();
            String filename = file.get("name").asText();

            try {
                StoreInfo storeInfo = parseStoreInfo(filename);
                if (storeInfo == null) {
                    log.debug("Could not parse store info from: {}", filename);
                    continue;
                }

                String csvContent = fetchString(client, csvUrl);
                List<ParsedPrice> storePrices = parseCsv(csvContent, storeInfo);
                allPrices.addAll(storePrices);
                count++;

                if (count % 50 == 0) {
                    log.info("Processed {}/{} Spar stores ({} prices so far)",
                            count, files.size(), allPrices.size());
                }
            } catch (Exception e) {
                log.warn("Failed to process Spar CSV {}: {}", filename, e.getMessage());
            }
        }

        log.info("Finished Spar ingestion: {} stores, {} total prices", count, allPrices.size());
        return allPrices;
    }

    private StoreInfo parseStoreInfo(String filename) {
        // Parse city from filename: type_city_parts_address_storeId_storeName_seq_date_time.csv
        // Examples:
        //   hipermarket_zadar_bleiburskih_zrtava_18_8701_interspar_zadar_0330_20260327_0330.csv
        //   supermarket_zagreb_savska_58_87079_spar_zg_savska_0298_20260223_0330.csv

        // Split by underscore
        String[] parts = filename.replace(".csv", "").split("_");
        if (parts.length < 6) return null;

        String storeType = parts[0]; // supermarket or hipermarket

        // Find the store ID (5-digit number starting with 87, or 4-digit starting with 87)
        int storeIdIndex = -1;
        String storeId = null;
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].matches("87\\d{1,3}") || parts[i].matches("87\\d{3}")) {
                storeIdIndex = i;
                storeId = parts[i];
                break;
            }
        }

        if (storeIdIndex < 0 || storeId == null) return null;

        // City is parts[1], capitalize it
        String city = parts[1].toUpperCase().replace(".", " ").trim();

        // Store name is everything between storeId and the sequence number
        StringBuilder storeName = new StringBuilder();
        for (int i = storeIdIndex + 1; i < parts.length; i++) {
            // Stop at the sequence number (4-digit number like 0330)
            if (parts[i].matches("\\d{4}") && i > storeIdIndex + 1) break;
            if (storeName.length() > 0) storeName.append(" ");
            storeName.append(parts[i]);
        }

        String displayName = "Spar " + capitalizeCity(city);

        return new StoreInfo(
                "SPAR_" + storeId,
                displayName,
                "", // street not reliably in filename
                capitalizeCity(city),
                0 // postal code not in filename
        );
    }

    private String capitalizeCity(String city) {
        if (city == null || city.isBlank()) return city;
        String[] words = city.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(word.substring(0, 1).toUpperCase()).append(word.substring(1));
        }
        return sb.toString();
    }

    private List<ParsedPrice> parseCsv(String csvContent, StoreInfo store) throws IOException {
        List<ParsedPrice> prices = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(csvContent));

        String header = reader.readLine();
        if (header == null) return prices;

        // Spar uses semicolons
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;

            String[] fields = line.split(";", -1);
            if (fields.length < 12) continue;

            try {
                String productName = fields[0].trim();
                String productCode = fields[1].trim();
                String brand = fields[2].trim();
                String netQuantity = fields[3].trim();
                String unit = fields[4].trim();
                BigDecimal retailPrice = parseCroatianDecimal(fields[5]);
                BigDecimal salePrice = parseCroatianDecimal(fields[7]);
                String barcode = fields[10].trim();
                String category = fields[11].trim();

                BigDecimal effectivePrice = salePrice != null ? salePrice : retailPrice;
                if (effectivePrice == null) continue;

                prices.add(new ParsedPrice(
                        productName, productCode, barcode, brand,
                        netQuantity, unit, category,
                        effectivePrice, salePrice,
                        store.code(), store.name(), "", store.city(), store.postalCode()
                ));
            } catch (Exception e) {
                // skip malformed lines
            }
        }

        return prices;
    }

    private BigDecimal parseCroatianDecimal(String value) {
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
