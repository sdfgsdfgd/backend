CREATE TABLE IF NOT EXISTS request_event (
    id bigserial,
    ts timestamptz NOT NULL DEFAULT now(),
    ip text NOT NULL,
    host text,
    method text,
    path text,
    raw_query text,
    status int,
    latency_ms int,
    ua text,
    matched_rule text,
    request_id text,
    suspicious_reason text,
    severity int,
    PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);

CREATE TABLE IF NOT EXISTS request_event_default
    PARTITION OF request_event DEFAULT;

CREATE INDEX IF NOT EXISTS request_event_ts_idx ON request_event (ts);
CREATE INDEX IF NOT EXISTS request_event_ip_ts_idx ON request_event (ip, ts);
CREATE INDEX IF NOT EXISTS request_event_reason_ts_idx ON request_event (suspicious_reason, ts);
