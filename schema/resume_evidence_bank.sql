-- Run manually in MySQL / DBeaver against database AIjob.
-- Do not use Flyway.

CREATE TABLE IF NOT EXISTS resume_evidence_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_resume_evidence_topic_entry
        (user_id, resume_id, topic_key, entry_key),

    INDEX idx_resume_evidence_resume
        (user_id, resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
