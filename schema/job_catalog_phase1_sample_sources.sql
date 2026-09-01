-- Optional: example job_sources rows.
-- Run only after schema/job_catalog_phase1.sql. Safe to re-run.
--
-- Every row is created with enabled = 0 AND crawl_allowed = 0, so applying this file
-- collects nothing. Collection requires an operator to review the board's terms and
-- then set BOTH flags for that row:
--
--   UPDATE job_sources
--      SET enabled = 1, crawl_allowed = 1
--    WHERE provider_type = 'GREENHOUSE' AND tenant_key = 'your-board-token';
--
-- tenant_key is the public board token / site slug, never a full URL:
--   Greenhouse  https://boards.greenhouse.io/<tenant_key>
--   Lever       https://jobs.lever.co/<tenant_key>
-- The collector always rebuilds the request URL from its own hard-coded official host,
-- so a tenant_key can never redirect it somewhere else.

INSERT IGNORE INTO job_sources (
    provider_type, display_name, tenant_key, base_url,
    enabled, crawl_allowed, terms_url, collection_interval_minutes
) VALUES
    ('GREENHOUSE', 'Example Corp (replace before enabling)', 'example-greenhouse-board',
     'https://boards-api.greenhouse.io/v1/boards', 0, 0, NULL, 120),
    ('LEVER', 'Example Ltd (replace before enabling)', 'example-lever-site',
     'https://api.lever.co/v0/postings', 0, 0, NULL, 120);

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-08-31-job-catalog-phase1-sample-sources');
