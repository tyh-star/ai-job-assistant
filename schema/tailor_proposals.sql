-- Run manually in MySQL / DBeaver against database AIjob.
-- Do not use Flyway.

CREATE TABLE IF NOT EXISTS tailor_proposals (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    resume_id             BIGINT       NOT NULL,
    job_id                BIGINT       NOT NULL,
    mode                  VARCHAR(32)  NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    expires_at            DATETIME     NOT NULL,
    changes_json          JSON         NOT NULL,
    rejected_json         JSON         NOT NULL,
    generated_file_name   VARCHAR(255) NULL,
    generated_file_path   VARCHAR(512) NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_tailor_proposals_user_job (user_id, job_id),
    INDEX idx_tailor_proposals_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
