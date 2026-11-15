package com.invest.growth.ingestion;

import java.time.Instant;

/** A unit of content to index. {@code dedupKey} is the URL or text block that is SHA-256 hashed. */
public record IngestItem(String ticker, Source source, DocType docType, String dedupKey,
                         String text, Instant publishedAt) {
}
