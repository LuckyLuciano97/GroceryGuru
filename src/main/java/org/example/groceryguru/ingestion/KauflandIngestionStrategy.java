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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KauflandIngestionStrategy implements StoreIngestionStrategy {

    private static final Logger log = LoggerFactory.getLogger(KauflandIngestionStrategy.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${ingestion.kaufland.json-url:https://www.kaufland.hr/akcije-novosti/popis-mpc.assetSearch.id=assetList_1599847924.json}")
    private String jsonUrl;

    // Filename: Type_Address_City_StoreCode_Date_Time.csv
    // e.g. Supermarket_Barutanski_jarak_54_Zagreb_6230_21122025_7-30.csv
    // City is typically the word before the store code (4 digits)
    private static final Pattern STORE_CODE_PATTERN = Pattern.compile("_(\\d{4})_\\d{8}_");

    @Override
    public String getChainName() {
        return "Kaufland";
    }

    @Override
    public List<ParsedPrice> fetchAndParse() throws Exception {
        HttpClient client = createTrustingClient();
        List<ParsedPrice> allPrices = new ArrayList<>();

        // Step 1: Fetch JSON index
        log.info("Fetching Kaufland JSON index: {}", jsonUrl);
        String jsonContent = fetchString(client, jsonUrl);
        JsonNode files = mapper.readTree(jsonContent);

        log.info("Found {} Kaufland CSV files", files.size());

        // Step 2: Find the most recent files (group by store code, keep latest date)
        Map<String, JsonNode> latestByStore = new LinkedHashMap<>();
        for (JsonNode file : files) {
            String label = file.get("label").asText();
            Matcher m = STORE_CODE_PATTERN.matcher(label);
            if (m.find()) {
                String storeCode = m.group(1);
                // Later entries in the array tend to be more recent - just overwrite
                latestByStore.put(storeCode, file);
            }
        }

        log.info("Unique Kaufland stores: {}", latestByStore.size());

        // Step 3: Download and parse each CSV
        int count = 0;
        for (Map.Entry<String, JsonNode> entry : latestByStore.entrySet()) {
            JsonNode file = entry.getValue();
            String path = file.get("path").asText();
            String label = file.get("label").asText();
            String csvUrl = "https://www.kaufland.hr" + path;

            try {
                StoreInfo storeInfo = parseStoreInfo(label);
                if (storeInfo == null) {
                    log.debug("Could not parse store info from: {}", label);
                    continue;
                }

                String csvContent = fetchString(client, csvUrl);
                List<ParsedPrice> storePrices = parseCsv(csvContent, storeInfo);
                allPrices.addAll(storePrices);
                count++;

                if (count % 10 == 0) {
                    log.info("Processed {}/{} Kaufland stores ({} prices so far)",
                            count, latestByStore.size(), allPrices.size());
                }
            } catch (Exception e) {
                log.warn("Failed to process Kaufland CSV {}: {}", label, e.getMessage());
            }
        }

        log.info("Finished Kaufland ingestion: {} stores, {} total prices", count, allPrices.size());
        return allPrices;
    }

    private StoreInfo parseStoreInfo(String label) {
        // e.g. Supermarket_Barutanski_jarak_54_Zagreb_6230_21122025_7-30.csv
        // Find store code (4 digits before the date)
        Matcher m = STORE_CODE_PATTERN.matcher(label);
        if (!m.find()) return null;

        String storeCode = m.group(1);
        String beforeCode = label.substring(0, m.start());

        // Split by underscore, the last word before store code is typically the city
        String[] parts = beforeCode.split("_");
        if (parts.length < 3) return null;

        String city = parts[parts.length - 1].trim();
        if (city.isEmpty() && parts.length > 1) {
            city = parts[parts.length - 2].trim();
        }

        return new StoreInfo(
                "KAUFLAND_" + storeCode,
                "Kaufland " + city,
                "", // street not in filename
                city.toUpperCase(),
                0
        );
    }

    private List<ParsedPrice> parseCsv(String csvContent, StoreInfo store) throws IOException {
        List<ParsedPrice> prices = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(csvContent));

        String header = reader.readLine();
        if (header == null) return prices;

        // Kaufland uses TAB delimiter, 15 columns
        // 0: naziv proizvoda, 1: šifra, 2: marka, 3: neto količina, 4: jed mjere,
        // 5: MPC, 6: akc cijena, 7: kol jed mj, 8: jed mj, 9: cijena jed mj,
        // 10: MPC poseb oblik, 11: najniža MPC 30d, 12: sidrena cijena, 13: barkod, 14: kategorija
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;

            String[] fields = line.split("\t", -1);
            if (fields.length < 15) continue;

            try {
                String productName = fields[0].trim();
                String productCode = fields[1].trim();
                String brand = fields[2].trim();
                String netQuantity = fields[3].trim();
                String unit = fields[4].trim();
                BigDecimal retailPrice = parseCroatianDecimal(fields[5]);
                BigDecimal salePrice = parseCroatianDecimal(fields[6]);
                String barcode = fields[13].trim();
                String category = fields[14].trim();

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
        // Remove "A" flag if present (e.g. "7,99 A" for action price)
        String cleaned = value.trim().replaceAll("[A-Za-z]", "").trim().replace(",", ".");
        if (cleaned.isEmpty() || cleaned.equals(".")) return null;
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
