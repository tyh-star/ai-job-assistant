-- ---------------------------------------------------------------------------
-- 通用简历生成器 (universal/generic resume builder) -- multiple named drafts per
-- user, each holding a typed JSON snapshot of a blank, profession-agnostic
-- resume form (basic info, job target, education, skills, work/internship,
-- projects, volunteer, certificates, languages, leadership, publications,
-- hobbies, custom sections, plus optional career-materials facts).
--
-- Version: 2026-09-07-resume-builder
--
-- What this adds
-- ---------------
-- Two new tables:
--   resume_builder_drafts (id, user_id, title, template_type, target_role,
--       locale, schema_version, draft_json LONGTEXT, version BIGINT,
--       created_at, updated_at)
--   resume_builder_generation_requests (id, user_id, draft_id,
--       idempotency_key, resume_id NULL, created_at)
--
-- A user may hold ANY NUMBER of drafts -- `user_id` is indexed, not unique
-- (unlike site_feedback's one-row-per-user table). Every read/update/delete
-- goes through the app as `id + user_id` together (see
-- ResumeBuilderDraftMapper) so a draft belonging to another account is
-- indistinguishable from one that does not exist -- the API surfaces that as
-- a plain 404, never a 403 that would confirm the id exists.
--
-- `version` is a plain optimistic-concurrency counter, incremented by
-- exactly one on every successful UPDATE (`... SET version = version + 1
-- WHERE id = ? AND user_id = ? AND version = ?`). A caller whose UPDATE
-- matches zero rows is either not the owner (already 404'd) or is holding a
-- stale `version` from a save that already happened elsewhere -- the API
-- returns 409 for that, and the frontend must reload rather than overwrite.
-- This is what makes "two browser tabs, old tab autosaves last" impossible.
--
-- `draft_json` is never trusted as opaque storage: every write is first
-- deserialized into typed DTOs (ResumeBuilderDraftContent and its nested
-- classes) and validated (string lengths, list sizes, URL scheme, date
-- ranges, section order, non-negative bounded numbers, no control/illegal
-- XML characters) by ResumeBuilderDraftValidator -- see its javadoc. The
-- database column is LONGTEXT only because MySQL JSON columns are awkward to
-- diff/test across MySQL and H2; the validation discipline is what actually
-- keeps it safe, not the column type.
--
-- `resume_builder_generation_requests` exists solely so POST .../generate is
-- idempotent: the caller sends a client-generated `idempotencyKey` once per
-- generate action and resends the SAME key on any retry. The unique key on
-- (user_id, idempotency_key) is what a retry (or two racing tabs) collides
-- on -- the loser of that race is told "the resume this key already made is
-- id X" instead of writing a second DOCX + a second `resumes` row. See
-- ResumeBuilderServiceImpl#generateFromDraft.
--
-- Re-runnable: run via ./infra/aws/db-apply.sh, chained into the ordered
-- release at infra/aws/release.sh step 7 (after site_feedback.sql, before
-- the final structure verify). CREATE TABLE IF NOT EXISTS is a no-op on a
-- database that already has these tables, so a re-release re-applies this
-- harmlessly, exactly like the other files in this chain.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(128) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS resume_builder_drafts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    template_type VARCHAR(40) NOT NULL,
    target_role VARCHAR(200) NULL,
    locale VARCHAR(10) NOT NULL DEFAULT 'en',
    schema_version INT NOT NULL DEFAULT 1,
    draft_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_resume_builder_drafts_user (user_id),
    KEY idx_resume_builder_drafts_user_updated (user_id, updated_at),
    CONSTRAINT fk_resume_builder_drafts_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS resume_builder_generation_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    draft_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    resume_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resume_builder_gen_user_key (user_id, idempotency_key),
    KEY idx_resume_builder_gen_draft (draft_id),
    CONSTRAINT fk_resume_builder_gen_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --- verification ------------------------------------------------------

SET @tables := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE()
       AND table_name IN ('schema_migrations', 'resume_builder_drafts',
                          'resume_builder_generation_requests')
);

SET @columns := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND (
            (table_name = 'resume_builder_drafts' AND column_name IN
                ('user_id', 'title', 'template_type', 'target_role', 'locale',
                 'schema_version', 'draft_json', 'version', 'created_at', 'updated_at'))
         OR (table_name = 'resume_builder_generation_requests' AND column_name IN
                ('user_id', 'draft_id', 'idempotency_key', 'resume_id', 'created_at'))
       )
);

SET @indexes := (
    SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND index_name IN ('idx_resume_builder_drafts_user',
                          'idx_resume_builder_drafts_user_updated',
                          'uk_resume_builder_gen_user_key')
);

SELECT @tables AS tables_found_of_3,
       @columns AS columns_found_of_15,
       @indexes AS indexes_found_of_3;

SET @gate := IF(@tables = 3 AND @columns = 15 AND @indexes = 3,
    'SELECT 1',
    'SELECT 1 FROM resume_builder_migration_aborted_structure_incomplete_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-07-resume-builder');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
