package com.invest.growth.advisor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.invest.growth.rag.RetrievedChunk;

/** One persisted analysis (a row of advisory_history). */
public record StoredAdvisory(
        long id,
        Instant generatedAt,
        StockAdvisoryResponse advisory,
        String evidenceHash,
        List<RetrievedChunk> evidence,
        Integer quantity,
        BigDecimal avgPrice,
        BigDecimal lastPrice,
        BigDecimal totalChangePercent,
        String model) {
}
