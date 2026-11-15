package com.invest.growth.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.invest.growth.advisor.AdvisoryOutcome;
import com.invest.growth.advisor.StockAdvisoryResponse;
import com.invest.growth.portfolio.HoldingView;
import com.invest.growth.rag.RetrievedChunk;

/** Flat view of one portfolio row; advisory is null when the advisor could not produce one. */
public record DashboardRow(HoldingView holding, StockAdvisoryResponse advisory,
                           List<RetrievedChunk> evidence, String error, String analysedAt) {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    public static DashboardRow from(AdvisoryOutcome outcome) {
        return switch (outcome) {
            case AdvisoryOutcome.Success s -> new DashboardRow(s.holding(), s.advisory(), s.evidence(), null, TS.format(s.generatedAt()));
            case AdvisoryOutcome.Failure f -> new DashboardRow(f.holding(), null, List.of(), f.reason(), null);
        };
    }
}
