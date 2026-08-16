-- Run manually in MySQL / DBeaver against database AIjob.
-- Do not use Flyway.

ALTER TABLE jobs
    ADD COLUMN external_job_id VARCHAR(64) NULL AFTER status;

ALTER TABLE jobs
    ADD UNIQUE KEY uk_jobs_user_source_external (user_id, source, external_job_id);
