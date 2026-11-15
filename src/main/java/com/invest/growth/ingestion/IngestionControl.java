package com.invest.growth.ingestion;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/** Runtime switch and status for the scheduled ingestion job. */
@Component
public class IngestionControl {

    public record Status(boolean enabled, boolean running, Instant lastRun, int lastAdded, String lastOutcome,
                         int progressDone, int progressTotal, Instant nextRun) {
    }

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastRun;
    private volatile int lastAdded;
    private volatile String lastOutcome = "Not run yet";
    private final AtomicInteger progressDone = new AtomicInteger();
    private volatile int progressTotal;
    private volatile Instant lastTick;
    private final long fixedRateMs;

    public IngestionControl(IngestionProperties props) {
        this.fixedRateMs = props.fixedRateMs() > 0 ? props.fixedRateMs() : 300_000;
    }

    /** Called on every scheduler firing, including while stopped, to predict the next run. */
    void tick() {
        lastTick = Instant.now();
    }

    void begin(int total) {
        progressDone.set(0);
        progressTotal = total;
    }

    void step() {
        progressDone.incrementAndGet();
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    void finish(int added, String outcome) {
        lastRun = Instant.now();
        lastAdded = added;
        lastOutcome = outcome;
        running.set(false);
    }

    public Status status() {
        Instant next = enabled.get() && lastTick != null ? lastTick.plusMillis(fixedRateMs) : null;
        return new Status(enabled.get(), running.get(), lastRun, lastAdded, lastOutcome,
                progressDone.get(), progressTotal, next);
    }
}
