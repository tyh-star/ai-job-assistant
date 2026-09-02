-- Phase 1: self-hosted job catalog.
-- Additive only. Safe to re-run. Never drops, renames, or truncates existing objects.
-- Run manually against the production database AFTER infra/aws/backup.sh.
-- Do not use Flyway; this project applies incremental DDL by hand.
--
-- Existing `jobs` keeps its current meaning: jobs a user created, imported, or saved.
-- The tables below are a SHARED catalog and never replace `jobs`.

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(128) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- job_sources: which public ATS boards we are allowed to collect from.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_type VARCHAR(32) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    tenant_key VARCHAR(190) NOT NULL,
    base_url VARCHAR(500) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    crawl_allowed TINYINT(1) NOT NULL DEFAULT 0,
    terms_url VARCHAR(500) NULL,
    collection_interval_minutes INT NOT NULL DEFAULT 120,
    last_success_at DATETIME NULL,
    next_run_at DATETIME NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    -- Cross-process collection lease. The scheduled collector container and an
    -- operator's one-off `run --rm collector` are different processes, so the claim on
    -- a board has to live in the database they share.
    locked_by VARCHAR(64) NULL,
    locked_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_sources_provider_tenant (provider_type, tenant_key),
    KEY idx_job_sources_due (enabled, crawl_allowed, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The lease columns are added separately as well, so a database that already has
-- job_sources from an earlier run of this migration gains them too: CREATE TABLE IF
-- NOT EXISTS above is a no-op on an existing table and would silently skip them.
-- MySQL has no ADD COLUMN IF NOT EXISTS, hence the information_schema guard.
SET @ddl := (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'job_sources'
              AND COLUMN_NAME = 'locked_by'
        ),
        'SELECT 1',
        'ALTER TABLE job_sources ADD COLUMN locked_by VARCHAR(64) NULL, ADD COLUMN locked_at DATETIME NULL'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- job_catalog: normalized shared postings served to every user's search.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_catalog (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(500) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    description_text LONGTEXT NULL,
    description_html LONGTEXT NULL,
    location_text VARCHAR(500) NULL,
    city VARCHAR(190) NULL,
    region VARCHAR(190) NULL,
    country_code CHAR(2) NULL,
    remote_type VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED',
    employment_type VARCHAR(24) NOT NULL DEFAULT 'UNSPECIFIED',
    salary_min DECIMAL(15,2) NULL,
    salary_max DECIMAL(15,2) NULL,
    currency CHAR(3) NULL,
    canonical_url VARCHAR(1000) NULL,
    apply_url VARCHAR(1000) NULL,
    -- De-duplication keys: no scheme, no www, no fragment, no trailing slash, and
    -- known campaign parameters removed -- but any other query parameter is KEPT,
    -- because a job identifier often lives there (?gh_jid=, ?token=, ?jobId=).
    -- NULL when the URL is a careers landing page rather than one opening, so a
    -- generic page can never merge a whole board into one row.
    -- The display columns above keep the original, clickable URLs.
    canonical_url_norm VARCHAR(500) NULL,
    apply_url_norm VARCHAR(500) NULL,
    posted_at DATETIME NULL,
    expires_at DATETIME NULL,
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    fingerprint CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_catalog_fingerprint (fingerprint),
    KEY idx_job_catalog_canonical_url (canonical_url(255)),
    KEY idx_job_catalog_apply_url (apply_url(255)),
    KEY idx_job_catalog_canonical_url_norm (canonical_url_norm),
    KEY idx_job_catalog_apply_url_norm (apply_url_norm),
    KEY idx_job_catalog_status_posted (status, posted_at),
    KEY idx_job_catalog_status_country (status, country_code),
    KEY idx_job_catalog_status_remote (status, remote_type),
    KEY idx_job_catalog_status_employment (status, employment_type),
    KEY idx_job_catalog_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Keyword search index. MySQL 8 InnoDB FULLTEXT; the H2 test schema uses LIKE
-- instead, so JobCatalogSearchMapper picks its predicate from the SQL dialect.
-- Wrapped so re-running the migration does not fail on an existing index.
SET @ddl := (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'job_catalog'
              AND INDEX_NAME = 'ft_job_catalog_text'
        ),
        'SELECT 1',
        'ALTER TABLE job_catalog ADD FULLTEXT KEY ft_job_catalog_text (title, company_name, description_text)'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- job_source_records: one row per (source, external job), linked to a catalog job.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_source_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    -- utf8mb4_0900_bin, not the table default: an ATS requisition id is an opaque
    -- token, so "REQ-A", "req-a" and "REQ-A " are three ids. The table collation folds
    -- all three (case-insensitive AND PAD SPACE), and utf8mb4_bin still folds the last
    -- two -- measured on 8.4.11, both are PAD SPACE. Only 0900_bin is binary AND NO PAD.
    external_job_id VARCHAR(190) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    catalog_job_id BIGINT NULL,
    source_url VARCHAR(1000) NULL,
    apply_url VARCHAR(1000) NULL,
    payload_hash CHAR(64) NOT NULL,
    raw_payload MEDIUMTEXT NULL,
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_successful_run_id BIGINT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_source_records_source_external (source_id, external_job_id),
    KEY idx_job_source_records_catalog (catalog_job_id),
    KEY idx_job_source_records_active_seen (source_id, active, last_seen_at),
    CONSTRAINT fk_job_source_records_source
        FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_source_records_catalog
        FOREIGN KEY (catalog_job_id) REFERENCES job_catalog (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- job_ingest_runs: one row per collection attempt per source.
-- error_summary stores a short classified reason only; never secrets,
-- response bodies, or personal data.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_ingest_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    fetched_count INT NOT NULL DEFAULT 0,
    inserted_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    expired_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY idx_job_ingest_runs_source_started (source_id, started_at),
    CONSTRAINT fk_job_ingest_runs_source
        FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- user_saved_catalog_jobs: maps a catalog job to the personal `jobs` row that
-- Match / Quick Optimize / Enhanced Tailor keep using.
-- Deleting a catalog job is RESTRICTed while a user still has it saved, so
-- nothing in this feature can cascade into a user's own job rows.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_saved_catalog_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    catalog_job_id BIGINT NOT NULL,
    personal_job_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_saved_catalog_jobs_user_catalog (user_id, catalog_job_id),
    KEY idx_user_saved_catalog_jobs_personal (personal_job_id),
    CONSTRAINT fk_user_saved_catalog_jobs_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_saved_catalog_jobs_catalog
        FOREIGN KEY (catalog_job_id) REFERENCES job_catalog (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_saved_catalog_jobs_personal
        FOREIGN KEY (personal_job_id) REFERENCES jobs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Structure gate.
--
-- `CREATE TABLE IF NOT EXISTS` is a no-op against a table that already exists with
-- the WRONG shape -- for example one left behind by an earlier, interrupted attempt.
-- Without the gate below the script would then finish "successfully" and record a
-- completed migration over a half-built schema.
--
-- Note on atomicity: MySQL DDL commits implicitly. The statements above therefore do
-- NOT roll back as a unit, and this file must not be described as transactional. What
-- it guarantees instead is weaker but sufficient: every statement is additive and
-- re-runnable, nothing is dropped or truncated, and the completion marker is written
-- only after the resulting structure has been verified. A partial run leaves a partial
-- schema and NO marker; re-running finishes the job.
--
-- Recovery from a failure here is: read the report below, fix or drop ONLY the
-- incomplete new tables (never `jobs`, `users`, `resumes` or any other existing
-- table), and run this file again.
-- ---------------------------------------------------------------------------

SELECT table_name AS present_table
  FROM information_schema.TABLES
 WHERE table_schema = DATABASE()
   AND table_name IN ('schema_migrations', 'job_sources', 'job_catalog',
                      'job_source_records', 'job_ingest_runs', 'user_saved_catalog_jobs')
 ORDER BY table_name;

SET @tables := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE()
       AND table_name IN ('schema_migrations', 'job_sources', 'job_catalog',
                          'job_source_records', 'job_ingest_runs', 'user_saved_catalog_jobs')
);

SET @columns := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND (
            (table_name = 'job_sources' AND column_name IN
                ('provider_type', 'tenant_key', 'enabled', 'crawl_allowed',
                 'collection_interval_minutes', 'next_run_at', 'consecutive_failures',
                 'locked_by', 'locked_at'))
         OR (table_name = 'job_catalog' AND column_name IN
                ('title', 'company_name', 'description_text', 'status', 'fingerprint',
                 'canonical_url', 'apply_url', 'canonical_url_norm', 'apply_url_norm',
                 'first_seen_at', 'last_seen_at'))
         OR (table_name = 'job_source_records' AND column_name IN
                ('source_id', 'external_job_id', 'catalog_job_id', 'payload_hash',
                 'raw_payload', 'last_seen_at', 'active'))
         OR (table_name = 'job_ingest_runs' AND column_name IN
                ('source_id', 'status', 'fetched_count', 'inserted_count',
                 'updated_count', 'unchanged_count', 'expired_count', 'failed_count',
                 'error_summary'))
         OR (table_name = 'user_saved_catalog_jobs' AND column_name IN
                ('user_id', 'catalog_job_id', 'personal_job_id'))
       )
);

SET @indexes := (
    SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND index_name IN ('uk_job_sources_provider_tenant', 'idx_job_sources_due',
                          'ft_job_catalog_text', 'idx_job_catalog_apply_url_norm',
                          'idx_job_catalog_canonical_url_norm', 'idx_job_catalog_fingerprint',
                          'idx_job_catalog_status_posted',
                          'uk_job_source_records_source_external',
                          'idx_job_source_records_catalog',
                          'uk_user_saved_catalog_jobs_user_catalog')
);

SET @foreign_keys := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE table_schema = DATABASE()
       AND constraint_type = 'FOREIGN KEY'
       AND constraint_name IN ('fk_job_source_records_source', 'fk_job_source_records_catalog',
                               'fk_job_ingest_runs_source', 'fk_user_saved_catalog_jobs_user',
                               'fk_user_saved_catalog_jobs_catalog',
                               'fk_user_saved_catalog_jobs_personal')
);

SELECT @tables AS tables_found_of_6,
       @columns AS columns_found_of_39,
       @indexes AS indexes_found_of_10,
       @foreign_keys AS foreign_keys_found_of_6;

-- Aborts the script when anything above is missing. `mysql` stops at the first error
-- in batch mode, so the completion marker below is never reached on a partial schema.
SET @gate := IF(@tables = 6 AND @columns = 39 AND @indexes = 10 AND @foreign_keys = 6,
    'SELECT 1',
    'SELECT 1 FROM job_catalog_migration_aborted_structure_incomplete_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Only now is the migration allowed to call itself done.
INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-08-31-job-catalog-phase1');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
