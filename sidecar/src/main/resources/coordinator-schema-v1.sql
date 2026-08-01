CREATE TABLE IF NOT EXISTS coordinator_conversations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace   TEXT NOT NULL,
    role        TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conv_workspace ON coordinator_conversations(workspace);

CREATE TABLE IF NOT EXISTS coordinator_advice (
    id          TEXT PRIMARY KEY,
    workspace   TEXT NOT NULL,
    epic_ref    TEXT,
    type        TEXT NOT NULL,
    title       TEXT NOT NULL,
    body        TEXT NOT NULL,
    action_key  TEXT,
    created_at  TEXT NOT NULL,
    dismissed   INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_advice_workspace ON coordinator_advice(workspace, created_at);

CREATE TABLE IF NOT EXISTS coordinator_cases (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace       TEXT NOT NULL,
    task_type       TEXT NOT NULL,
    context_tokens  INTEGER NOT NULL,
    event_count     INTEGER NOT NULL,
    conv_depth      INTEGER NOT NULL,
    model_used      TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    outcome         TEXT,
    outcome_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_cases_type ON coordinator_cases(task_type);
CREATE INDEX IF NOT EXISTS idx_cases_outcome ON coordinator_cases(outcome);
