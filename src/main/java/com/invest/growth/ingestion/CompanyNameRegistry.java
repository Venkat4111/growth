package com.invest.growth.ingestion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Company names discovered on Screener, used to match ET news to tickers. */
@Component
public class CompanyNameRegistry {

    private final Map<String, String> names = new ConcurrentHashMap<>();

    public void put(String ticker, String name) {
        if (name != null && !name.isBlank()) {
            names.put(ticker, name.trim());
        }
    }

    public String get(String ticker) {
        return names.get(ticker);
    }
}
