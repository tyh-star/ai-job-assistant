-- Empty-database bootstrap for first public EC2 deploy.
-- Matches the current production tables. Does not change schema/*.sql.
-- schema/*.sql contains later ALTER scripts that fail on a missing base table;
-- do not apply those ALTERs to a fresh database.
-- MySQL official image runs this directory only when the data volume is empty.

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS resumes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    extracted_text LONGTEXT,
    parent_resume_id BIGINT NULL,
    canonical_resume_id BIGINT NULL,
    origin VARCHAR(32) NOT NULL DEFAULT 'UPLOAD',
    review_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    structured_json JSON NULL,
    layout_json JSON NULL,
    extraction_status VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY user_id (user_id),
    KEY idx_resumes_parent (parent_resume_id),
    KEY idx_resumes_extraction_status (extraction_status),
    CONSTRAINT resumes_ibfk_1 FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    company VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    location VARCHAR(255) DEFAULT NULL,
    job_url VARCHAR(1000) DEFAULT NULL,
    description LONGTEXT NOT NULL,
    source VARCHAR(100) DEFAULT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'SAVED',
    external_job_id VARCHAR(64) DEFAULT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jobs_user_source_external (user_id, source, external_job_id),
    CONSTRAINT jobs_ibfk_1 FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tailor_proposals (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME NOT NULL,
    changes_json JSON NOT NULL,
    rejected_json JSON NOT NULL,
    generated_file_name VARCHAR(255) NULL,
    generated_file_path VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tailor_proposals_user_job (user_id, job_id),
    KEY idx_tailor_proposals_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS resume_evidence_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    topic_key VARCHAR(128) NOT NULL,
    topic_label VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    answer_status VARCHAR(32) NOT NULL,
    entry_key VARCHAR(255) NOT NULL DEFAULT 'GENERAL',
    entry_label VARCHAR(255) NULL,
    related_node_ids_json JSON NULL,
    context_text TEXT NULL,
    action_text TEXT NULL,
    result_text TEXT NULL,
    metric_text VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_evidence_topic_entry (user_id, resume_id, topic_key, entry_key),
    KEY idx_resume_evidence_resume (user_id, resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_requirement_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    description_sha256 CHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    extraction_model VARCHAR(64) NOT NULL,
    requirements_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_requirement_profile_user_job (user_id, job_id),
    KEY idx_job_requirement_profile_hash (user_id, job_id, description_sha256, schema_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Phase 1 shared job catalog. Kept in sync with schema/job_catalog_phase1.sql,
-- which is the incremental migration for databases that already have data.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(128) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    locked_by VARCHAR(64) NULL,
    locked_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_sources_provider_tenant (provider_type, tenant_key),
    KEY idx_job_sources_due (enabled, crawl_allowed, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    KEY idx_job_catalog_last_seen (last_seen_at),
    FULLTEXT KEY ft_job_catalog_text (title, company_name, description_text)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
