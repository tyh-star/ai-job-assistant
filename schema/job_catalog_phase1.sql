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
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_sources_provider_tenant (provider_type, tenant_key),
    KEY idx_job_sources_due (enabled, crawl_allowed, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    -- Normalized copies (no scheme, no www, no query, no trailing slash) exist so
    -- cross-source de-duplication can match on an index instead of scanning.
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
    external_job_id VARCHAR(190) NOT NULL,
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

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-08-31-job-catalog-phase1');
