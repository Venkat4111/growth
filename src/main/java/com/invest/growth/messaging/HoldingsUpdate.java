package com.invest.growth.messaging;

import java.time.Instant;
import java.util.List;

import com.invest.growth.portfolio.HoldingView;

public record HoldingsUpdate(Instant fetchedAt, List<HoldingView> holdings) {
}
