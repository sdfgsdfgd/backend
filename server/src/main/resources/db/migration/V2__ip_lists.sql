CREATE TABLE IF NOT EXISTS ip_blacklist (
    ip text PRIMARY KEY,
    reason text,
    country_code text,
    first_seen timestamptz NOT NULL DEFAULT now(),
    last_seen timestamptz NOT NULL DEFAULT now(),
    hits bigint NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS ip_allowlist (
    ip text PRIMARY KEY,
    note text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ip_blacklist_last_seen_idx ON ip_blacklist(last_seen);
