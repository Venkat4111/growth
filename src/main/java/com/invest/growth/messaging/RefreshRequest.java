package com.invest.growth.messaging;

import java.time.Instant;

public record RefreshRequest(Instant requestedAt) {
}
