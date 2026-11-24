PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS task (
    id          TEXT PRIMARY KEY,
    text        TEXT NOT NULL DEFAULT '',
    ink         TEXT,
    status      TEXT NOT NULL CHECK (status IN ('open', 'done', 'unsorted')),
    done_day    TEXT,
    seam        TEXT,
    ord         INTEGER NOT NULL,
    src         TEXT NOT NULL DEFAULT 'type',
    due         TEXT,
    quote       TEXT,
    capture_id  TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    archived    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS task_live ON task (archived, status, done_day);

CREATE TABLE IF NOT EXISTS capture (
    id           TEXT PRIMARY KEY,
    captured_at  TEXT NOT NULL,
    transcript   TEXT NOT NULL,
    received_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS page (
    day      TEXT PRIMARY KEY,
    strokes  TEXT NOT NULL,
    updated  INTEGER NOT NULL
);
