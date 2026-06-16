package org.example.groceryguru.controller;

import org.example.groceryguru.ingestion.CijeneApiIngestionService;
import org.example.groceryguru.ingestion.IngestionResult;
import org.example.groceryguru.ingestion.PriceIngestionService;
import org.example.groceryguru.ingestion.StoreIngestionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ingestion")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final PriceIngestionService ingestionService;
    private final CijeneApiIngestionService cijeneApiIngestionService;
    private final List<StoreIngestionStrategy> strategies;
    private final JdbcTemplate jdbc;

    public IngestionController(PriceIngestionService ingestionService,
                                CijeneApiIngestionService cijeneApiIngestionService,
                                List<StoreIngestionStrategy> strategies,
                                JdbcTemplate jdbc) {
        this.ingestionService = ingestionService;
        this.cijeneApiIngestionService = cijeneApiIngestionService;
        this.strategies = strategies;
        this.jdbc = jdbc;
    }

    /**
     * Stats endpoint to track encoding-corruption cleanup progress.
     * Counts products whose {@code name} or {@code brand} still contain the
     * U+FFFD replacement character - those are leftovers from the old buggy
     * UTF-8 read of Windows-1250 CSVs in {@link CijeneApiIngestionService}.
     * A successful re-ingestion should drive these counts to zero.
     */
    @GetMapping("/encoding-stats")
    public Map<String, Object> encodingStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalProducts",
                jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class));
        out.put("corruptedNames", jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE name LIKE '%�%'", Long.class));
        out.put("corruptedBrands", jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE brand LIKE '%�%'", Long.class));
        out.put("corruptedDisplayNames", jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE display_name LIKE '%�%'", Long.class));
        return out;
    }

    @PostMapping("/run")
    public ResponseEntity<IngestionResult> runAll() {
        IngestionResult result = ingestionService.ingestAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/run/{chainName}")
    public ResponseEntity<?> runForChain(@PathVariable String chainName) {
        StoreIngestionStrategy strategy = strategies.stream()
                .filter(s -> s.getChainName().equalsIgnoreCase(chainName))
                .findFirst()
                .orElse(null);

        if (strategy == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Unknown chain: " + chainName));
        }

        try {
            IngestionResult result = ingestionService.ingestFromStrategy(strategy);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Ingestion failed: " + e.getMessage()));
        }
    }

    @GetMapping("/chains")
    public List<String> getAvailableChains() {
        return strategies.stream()
                .map(StoreIngestionStrategy::getChainName)
                .toList();
    }

    /**
     * Downloads and ingests today's archive from the public cijene-api
     * (https://api.cijene.dev). Contains ~20 Croatian chains in a single ZIP.
     * Runs asynchronously - returns immediately. Track progress in server logs.
     */
    @PostMapping("/cijene-api/run")
    public ResponseEntity<Map<String, Object>> runCijeneApi() {
        new Thread(() -> {
            try {
                IngestionResult result = cijeneApiIngestionService.ingestLatest();
                log.info("Cijene API ingestion finished: {}", result);
            } catch (Exception e) {
                log.error("Cijene API ingestion failed", e);
            }
        }, "cijene-api-ingestion").start();

        return ResponseEntity.ok(Map.of(
                "message", "Cijene API ingestion started (all ~20 chains). Check server logs for progress.",
                "source", "https://api.cijene.dev/v0/archive/<today>.zip"
        ));
    }

    /**
     * Ingests the archive for a specific date (format: YYYY-MM-DD).
     * Useful for backfills or reprocessing a particular day.
     */
    @PostMapping("/cijene-api/run/{date}")
    public ResponseEntity<Map<String, Object>> runCijeneApiForDate(@PathVariable String date) {
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Invalid date format (expected YYYY-MM-DD): " + date
            ));
        }

        new Thread(() -> {
            try {
                IngestionResult result = cijeneApiIngestionService.ingestForDate(parsed);
                log.info("Cijene API ingestion for {} finished: {}", parsed, result);
            } catch (Exception e) {
                log.error("Cijene API ingestion for {} failed", parsed, e);
            }
        }, "cijene-api-ingestion-" + parsed).start();

        return ResponseEntity.ok(Map.of(
                "message", "Cijene API ingestion started for " + parsed + ". Check server logs for progress.",
                "source", "https://api.cijene.dev/v0/archive/" + parsed + ".zip"
        ));
    }
}
