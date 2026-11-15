package com.invest.growth.ingestion;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        long fixedRateMs,
        int httpTimeoutMs,
        String userAgent,
        String screenerBaseUrl,
        String screenerSessionCookie,
        List<String> etRssFeeds,
        int maxConcurrency,
        int chunkSize) {

    public IngestionProperties {
        screenerSessionCookie = screenerSessionCookie == null ? "" : screenerSessionCookie;
        etRssFeeds = etRssFeeds == null ? List.of() : List.copyOf(etRssFeeds);
        maxConcurrency = maxConcurrency <= 0 ? 3 : maxConcurrency;
        chunkSize = chunkSize <= 0 ? 1500 : chunkSize;
    }
}
