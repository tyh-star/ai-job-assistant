-- Run manually in MySQL / DBeaver against database AIjob.
-- Do not use Flyway.

CREATE TABLE IF NOT EXISTS job_requirement_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    description_sha256 CHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    extraction_model VARCHAR(64) NOT NULL,
    requirements_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_job_requirement_profile_user_job (user_id, job_id),
    INDEX idx_job_requirement_profile_hash
        (user_id, job_id, description_sha256, schema_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
