package org.example.groceryguru.ingestion;

public record IngestionResult(
        int processed,
        int created,
        int updated,
        int errors
) {}
