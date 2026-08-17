-- Run manually in MySQL / DBeaver against database AIjob.
-- Do not use Flyway.

ALTER TABLE resumes
    ADD COLUMN parent_resume_id BIGINT NULL AFTER extracted_text,
    ADD COLUMN canonical_resume_id BIGINT NULL AFTER parent_resume_id,
    ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'UPLOAD' AFTER canonical_resume_id,
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'READY' AFTER origin,
    ADD COLUMN structured_json JSON NULL AFTER review_status;

ALTER TABLE resumes
    ADD INDEX idx_resumes_parent (parent_resume_id);

-- Phase 2B: layout blocks + extraction status. Run even if Phase 2A columns already exist.

ALTER TABLE resumes
    ADD COLUMN layout_json JSON NULL AFTER structured_json,
    ADD COLUMN extraction_status VARCHAR(32) NOT NULL DEFAULT 'TEXT' AFTER layout_json;

ALTER TABLE resumes
    ADD INDEX idx_resumes_extraction_status (extraction_status);
