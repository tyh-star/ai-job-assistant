-- H2 (MySQL mode) equivalent of the tables the job catalog touches.
-- Semantics match infra/aws/mysql-init/01-schema.sql; the MySQL-only bits
-- (FULLTEXT, prefix indexes, storage clauses) are dropped because H2 rejects them
-- and SqlDialect already routes keyword search to LIKE outside MySQL.

DROP TABLE IF EXISTS user_saved_catalog_jobs;
DROP TABLE IF EXISTS job_ingest_runs;
DROP TABLE IF EXISTS job_source_records;
DROP TABLE IF EXISTS job_catalog;
DROP TABLE IF EXISTS job_sources;
DROP TABLE IF EXISTS jobs;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'USER',
    -- Bumped by 1 every time the password changes (see UserMapper#changePasswordAndBumpAuthVersion).
    -- JwtAuthenticationFilter rejects any token whose authVersion claim no longer
    -- matches this column, which is how a password change invalidates every
    -- previously-issued token -- not just the browser that changed it.
    auth_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    company VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    location VARCHAR(255) DEFAULT NULL,
    job_url VARCHAR(1000) DEFAULT NULL,
    description CLOB NOT NULL,
    source VARCHAR(100) DEFAULT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'SAVED',
    external_job_id VARCHAR(64) DEFAULT NULL,
    applied TINYINT NOT NULL DEFAULT 0,
    applied_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_jobs_user_source_external UNIQUE (user_id, source, external_job_id),
    CONSTRAINT jobs_ibfk_1 FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE job_sources (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_type VARCHAR(32) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    tenant_key VARCHAR(190) NOT NULL,
    base_url VARCHAR(500) NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    crawl_allowed TINYINT NOT NULL DEFAULT 0,
    terms_url VARCHAR(500) NULL,
    collection_interval_minutes INT NOT NULL DEFAULT 120,
    last_success_at TIMESTAMP NULL,
    next_run_at TIMESTAMP NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    locked_by VARCHAR(64) NULL,
    locked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_sources_provider_tenant UNIQUE (provider_type, tenant_key)
);

CREATE TABLE job_catalog (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    description_text CLOB NULL,
    description_html CLOB NULL,
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
    canonical_url_norm VARCHAR(500) NULL,
    apply_url_norm VARCHAR(500) NULL,
    posted_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_job_catalog_fingerprint ON job_catalog (fingerprint);
CREATE INDEX idx_job_catalog_canonical_url_norm ON job_catalog (canonical_url_norm);
CREATE INDEX idx_job_catalog_apply_url_norm ON job_catalog (apply_url_norm);
CREATE INDEX idx_job_catalog_status_posted ON job_catalog (status, posted_at);

CREATE TABLE job_source_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    external_job_id VARCHAR(190) NOT NULL,
    catalog_job_id BIGINT NULL,
    source_url VARCHAR(1000) NULL,
    apply_url VARCHAR(1000) NULL,
    payload_hash CHAR(64) NOT NULL,
    raw_payload CLOB NULL,
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_successful_run_id BIGINT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_source_records_source_external UNIQUE (source_id, external_job_id),
    CONSTRAINT fk_job_source_records_source
        FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_source_records_catalog
        FOREIGN KEY (catalog_job_id) REFERENCES job_catalog (id) ON DELETE SET NULL
);

CREATE TABLE job_ingest_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    fetched_count INT NOT NULL DEFAULT 0,
    inserted_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    expired_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(500) NULL,
    CONSTRAINT fk_job_ingest_runs_source
        FOREIGN KEY (source_id) REFERENCES job_sources (id) ON DELETE CASCADE
);

CREATE TABLE user_saved_catalog_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    catalog_job_id BIGINT NOT NULL,
    personal_job_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_saved_catalog_jobs_user_catalog UNIQUE (user_id, catalog_job_id),
    CONSTRAINT fk_user_saved_catalog_jobs_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_saved_catalog_jobs_catalog
        FOREIGN KEY (catalog_job_id) REFERENCES job_catalog (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_saved_catalog_jobs_personal
        FOREIGN KEY (personal_job_id) REFERENCES jobs (id) ON DELETE CASCADE
);
