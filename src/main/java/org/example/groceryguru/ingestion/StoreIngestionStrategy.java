package org.example.groceryguru.ingestion;

import java.util.List;

public interface StoreIngestionStrategy {

    String getChainName();

    List<ParsedPrice> fetchAndParse() throws Exception;
}
