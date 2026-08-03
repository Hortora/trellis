CREATE TABLE IF NOT EXISTS coordinator_actions (
    id               TEXT PRIMARY KEY,
    advice_id        TEXT NOT NULL,
    category         TEXT NOT NULL,
    action_type      TEXT NOT NULL,
    params           TEXT NOT NULL,
    risk             TEXT NOT NULL,
    rationale        TEXT NOT NULL,
    status           TEXT NOT NULL,
    workspace        TEXT NOT NULL,
    proposed_at      TEXT NOT NULL,
    resolved_at      TEXT,
    execution_result TEXT,
    FOREIGN KEY (advice_id) REFERENCES coordinator_advice(id)
);
CREATE INDEX IF NOT EXISTS idx_actions_workspace ON coordinator_actions(workspace, status);
CREATE INDEX IF NOT EXISTS idx_actions_advice ON coordinator_actions(advice_id);
