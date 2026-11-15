CREATE TABLE IF NOT EXISTS advisory_history (
    id                   BIGSERIAL PRIMARY KEY,
    ticker               TEXT          NOT NULL,
    generated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    risk_zone            TEXT          NOT NULL,
    action               TEXT          NOT NULL,
    time_limit           TEXT,
    management_summary   TEXT,
    rationale_points     JSONB         NOT NULL DEFAULT '[]',
    evidence             JSONB         NOT NULL DEFAULT '[]',
    evidence_hash        TEXT          NOT NULL,
    quantity             INT,
    avg_price            NUMERIC(14,2),
    last_price           NUMERIC(14,2),
    total_change_percent NUMERIC(10,2),
    model                TEXT
);

CREATE INDEX IF NOT EXISTS idx_advisory_history_ticker_time
    ON advisory_history (ticker, generated_at DESC);
