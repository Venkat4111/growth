package com.invest.growth.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(int topK, double similarityThreshold) {

    public RagProperties {
        topK = topK <= 0 ? 8 : topK;
    }
}
