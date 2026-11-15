package com.invest.growth.advisor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "advisor")
public record AdvisorProperties(long cacheTtlMs, int maxConcurrency) {

    public AdvisorProperties {
        cacheTtlMs = cacheTtlMs <= 0 ? 300_000 : cacheTtlMs;
        maxConcurrency = maxConcurrency <= 0 ? 4 : maxConcurrency;
    }
}
