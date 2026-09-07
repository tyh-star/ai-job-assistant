-- ---------------------------------------------------------------------------
-- "Did the user actually apply" tracking on a saved personal job.
--
-- Version: 2026-09-04-jobs-applied-tracking
--
-- What this adds
-- ---------------
-- Two columns on `jobs`, separate from `status` (which already carries values like
-- 'SAVED' for a different purpose and is not overloaded here):
--   applied     TINYINT(1) NOT NULL DEFAULT 0
--   applied_at  TIMESTAMP  NULL     DEFAULT NULL
--
-- `applied_at` is a real timestamp of the user's own confirmation, never of the click
-- on the external apply link -- this backend never marks a job applied on its own; see
-- JobCatalogService#markApplied. Confirming again just refreshes `applied_at`, which is
-- why there is no unique/history table here: one flag and one timestamp per personal
-- job is the whole state.
--
-- Re-runnable: run via ./infra/aws/db-apply.sh, chained into the ordered release at
-- infra/aws/release.sh step 7 (after job_catalog_phase1.sql and the two external-id
-- migrations, before the final structure verify). Each ALTER is skipped when the column
-- already exists, and the completion marker is INSERT IGNORE, so a re-release re-applies
-- this harmlessly, exactly like the other files in this chain.
--
-- Not atomic, same as every other file here: MySQL commits DDL implicitly. What this
-- guarantees instead is a structure gate at the end (both columns present, correct
-- type/nullability/default) and a schema_migrations row written only if that gate
-- passes -- a partial run leaves no completion marker, and running this file again
-- finishes the job.
-- ---------------------------------------------------------------------------

-- --- preconditions ---------------------------------------------------------
SET @jobs_table_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE() AND table_name = 'jobs'
);
SET @gate := IF(@jobs_table_exists = 1,
    'SELECT 1',
    'SELECT 1 FROM applied_tracking_migration_aborted_jobs_table_missing');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @schema_migrations_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE() AND table_name = 'schema_migrations'
);
SET @gate := IF(@schema_migrations_exists = 1,
    'SELECT 1',
    'SELECT 1 FROM applied_tracking_migration_aborted_schema_migrations_table_missing');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- the change --------------------------------------------------------

SET @needs_applied := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied'
) = 0;
SET @alter := IF(@needs_applied,
    'ALTER TABLE jobs ADD COLUMN applied TINYINT(1) NOT NULL DEFAULT 0 AFTER external_job_id',
    'SELECT 1');
PREPARE stmt FROM @alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @needs_applied_at := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied_at'
) = 0;
SET @alter := IF(@needs_applied_at,
    'ALTER TABLE jobs ADD COLUMN applied_at TIMESTAMP NULL DEFAULT NULL AFTER applied',
    'SELECT 1');
PREPARE stmt FROM @alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- verification ------------------------------------------------------

SET @applied_type := (
    SELECT data_type FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied'
);
SET @applied_nullable := (
    SELECT is_nullable FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied'
);
SET @applied_default := (
    SELECT column_default FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied'
);
SET @applied_at_type := (
    SELECT data_type FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied_at'
);
SET @applied_at_nullable := (
    SELECT is_nullable FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'jobs' AND column_name = 'applied_at'
);

SELECT @applied_type AS applied_data_type,
       @applied_nullable AS applied_is_nullable,
       @applied_default AS applied_column_default,
       @applied_at_type AS applied_at_data_type,
       @applied_at_nullable AS applied_at_is_nullable;

SET @gate := IF(@applied_type = 'tinyint' AND @applied_nullable = 'NO' AND @applied_default = '0'
                AND @applied_at_type = 'timestamp' AND @applied_at_nullable = 'YES',
    'SELECT 1',
    'SELECT 1 FROM applied_tracking_migration_aborted_structure_wrong_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-04-jobs-applied-tracking');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
