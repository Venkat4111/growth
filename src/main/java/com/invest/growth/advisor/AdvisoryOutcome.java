package com.invest.growth.advisor;

import java.time.Instant;
import java.util.List;

import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.rag.RetrievedChunk;

public sealed interface AdvisoryOutcome {

    HoldingView holding();

    Instant generatedAt();

    record Success(HoldingView holding, StockAdvisoryResponse advisory,
                   List<RetrievedChunk> evidence, Instant generatedAt) implements AdvisoryOutcome {
    }

    record Failure(HoldingView holding, String reason, Instant generatedAt) implements AdvisoryOutcome {
    }
}
