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
