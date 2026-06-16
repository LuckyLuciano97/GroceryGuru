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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class PlodineIngestionStrategy implements StoreIngestionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlodineIngestionStrategy.class);

    @Value("${ingestion.plodine.url-pattern:https://www.plodine.hr/cjenici/cjenici_{date}_07_00_01.zip}")
    private String urlPattern;

    private static final Pattern FILENAME_PATTERN = Pattern.compile(
            "^(HIPERMARKET|SUPERMARKET)_(.+?)_(\\d{5})_(.+?)_(\\d{3})_(\\d{3})_\\d+\\.csv$"
    );

    @Override
    public String getChainName() {
        return "Plodine";
    }

    @Override
    public List<ParsedPrice> fetchAndParse() throws Exception {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd_MM_yyyy"));
        String url = urlPattern.replace("{date}", date);
        log.info("Downloading Plodine price list from: {}", url);

        byte[] zipBytes = downloadZip(url);
        return parseZip(zipBytes);
    }

    private HttpClient createTrustingClient() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private byte[] downloadZip(String url) throws Exception {
        HttpClient client = createTrustingClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download Plodine ZIP: HTTP " + response.statusCode());
        }

        log.info("Downloaded {} bytes", response.body().length);
        return response.body();
    }

    private List<ParsedPrice> parseZip(byte[] zipBytes) throws IOException {
        List<ParsedPrice> allPrices = new ArrayList<>();
        Map<String, String> latestFilePerStore = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                Matcher m = FILENAME_PATTERN.matcher(name);
                if (!m.matches()) continue;

                String storeCode = m.group(5);
                String batchNumber = m.group(6);

                String existing = latestFilePerStore.get(storeCode);
                if (existing == null || batchNumber.compareTo(extractBatch(existing)) > 0) {
                    latestFilePerStore.put(storeCode, name);
                }
            }
        }

        log.info("Found {} unique stores, picking latest batch per store", latestFilePerStore.size());

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!latestFilePerStore.containsValue(name)) continue;

                Matcher m = FILENAME_PATTERN.matcher(name);
                if (!m.matches()) continue;

                String storeType = m.group(1);
                String addressPart = m.group(2).replace("_", " ");
                int postalCode = Integer.parseInt(m.group(3));
                String city = m.group(4).replace("_", " ");
                String storeCode = m.group(5);

                String storeName = "Plodine " + city;

                List<ParsedPrice> storePrices = parseCsv(zis, storeCode, storeName, addressPart, city, postalCode);
                allPrices.addAll(storePrices);
                log.info("Parsed {} prices for store {} ({})", storePrices.size(), storeCode, storeName);
            }
        }

        return allPrices;
    }

    private String extractBatch(String filename) {
        Matcher m = FILENAME_PATTERN.matcher(filename);
        return m.matches() ? m.group(6) : "000";
    }

    private List<ParsedPrice> parseCsv(InputStream is, String storeCode, String storeName, String street,
                                        String city, int postalCode) throws IOException {
        List<ParsedPrice> prices = new ArrayList<>();
        // Read all bytes first so we can detect encoding
        // Croatian store CSVs are typically windows-1250 encoded
        byte[] bytes = is.readAllBytes();
        String content = EncodingUtil.detectAndDecode(bytes);
        BufferedReader reader = new BufferedReader(new StringReader(content));

        String header = reader.readLine();
        if (header == null) return prices;

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
                String unitMeasure = fields[4].trim();
                BigDecimal retailPrice = parseCroatianDecimal(fields[5]);
                BigDecimal salePrice = parseCroatianDecimal(fields[7]);
                String barcode = fields[10].trim();
                String category = fields[11].trim();

                if (retailPrice == null) continue;

                prices.add(new ParsedPrice(
                        productName, productCode, barcode, brand,
                        netQuantity, unitMeasure, category,
                        retailPrice, salePrice,
                        "PLODINE_" + storeCode, storeName, street, city, postalCode
                ));
            } catch (Exception e) {
                log.debug("Skipping malformed line: {}", line);
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
}
