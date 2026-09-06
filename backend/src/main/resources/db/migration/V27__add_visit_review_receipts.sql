CREATE TABLE visit_review_receipts (
    visit_id BIGINT PRIMARY KEY REFERENCES visits(id) ON DELETE RESTRICT,
    review_required BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO visit_review_receipts (visit_id, review_required, created_at)
SELECT id, status = 'visited', created_at
FROM visits;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stog_app') THEN
        GRANT SELECT, INSERT ON visit_review_receipts TO stog_app;
    END IF;
END
$$;
