package com.invest.growth.rag;

import java.time.Instant;

public record RetrievedChunk(String ticker, String source, String docType, Instant publishedAt, String text) {
}
