package com.invest.growth.advisor;

import java.util.List;

public record StockAdvisoryResponse(
        String ticker,
        RiskZone riskZone,
        TradeAction action,
        String timeLimit,
        String managementSummary,
        List<String> rationalePoints) {
}
