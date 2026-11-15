package com.invest.growth.portfolio;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/** Holds the daily Kite access token in memory; Kite tokens expire around 06:00 IST. */
@Component
public class KiteSessionStore {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private record Session(String accessToken, String userId, Instant expiresAt) {
    }

    private final AtomicReference<Session> session = new AtomicReference<>();
    private final Clock clock = Clock.system(IST);

    public void save(String accessToken, String userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime expiry = now.toLocalDate().atTime(6, 0).atZone(IST);
        if (!expiry.isAfter(now)) {
            expiry = expiry.plusDays(1);
        }
        session.set(new Session(accessToken, userId, expiry.toInstant()));
    }

    public Optional<String> accessToken() {
        return valid().map(Session::accessToken);
    }

    public Optional<String> userId() {
        return valid().map(Session::userId);
    }

    public void clear() {
        session.set(null);
    }

    private Optional<Session> valid() {
        Session s = session.get();
        if (s == null || !s.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(s);
    }
}
