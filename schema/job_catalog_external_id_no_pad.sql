-- ---------------------------------------------------------------------------
-- Stop trailing spaces from folding a posting's external id.
--
-- Version: 2026-09-01-job-source-records-external-id-no-pad
-- Requires: 2026-09-01-job-source-records-external-id-binary
--
-- The remaining defect
-- --------------------
-- The previous migration made external_job_id utf8mb4_bin, which fixed letter case. It
-- did not fix everything: utf8mb4_bin is a PAD SPACE collation, so it still compares a
-- value against the same value with trailing spaces as EQUAL.
--
-- Measured on the isolated MySQL 8.4.11, not assumed:
--
--   collation           PAD_ATTRIBUTE   'REQ-A' = 'REQ-A '   'REQ-A' = 'req-a'
--   utf8mb4_unicode_ci  PAD SPACE       (folded)             1  <- the original bug
--   utf8mb4_bin         PAD SPACE       1  <- still folded   0
--   utf8mb4_0900_bin    NO PAD          0                    0
--
-- So on utf8mb4_bin a board publishing "REQ-A" and "REQ-A " still collides on
-- uk_job_source_records_source_external, and the second posting overwrites the first --
-- the same failure as the case bug, through a different door.
--
-- The fix
-- -------
-- utf8mb4_0900_bin: binary comparison AND NO PAD. It is the strictly stronger of the two
-- for an identifier, and it is what a MySQL 8.x deployment should use for one.
--
-- Scope
-- -----
-- The same single column. VARCHAR(190), NOT NULL, the unique key and every other column
-- are unchanged; the database collation is untouched; job_catalog's human-text columns
-- stay utf8mb4_unicode_ci so search remains case-insensitive.
--
-- Direction of travel is safe for the same reason as before: PAD SPACE to NO PAD can only
-- SPLIT keys that previously collided, never create a collision, so the ALTER cannot fail
-- on duplicate rows.
--
-- What this does NOT do
-- ---------------------
-- It does not recover rows already overwritten by a fold, whether by case or by padding.
-- Only a fresh collection from the source can bring those back.
--
-- Re-runnable: the ALTER is skipped when the column is already utf8mb4_0900_bin, and the
-- completion marker is INSERT IGNORE.
-- ---------------------------------------------------------------------------

-- --- preconditions ---------------------------------------------------------
SET @previous := (
    SELECT COUNT(*) FROM schema_migrations
     WHERE version = '2026-09-01-job-source-records-external-id-binary'
);
SET @gate := IF(@previous = 1,
    'SELECT 1',
    'SELECT 1 FROM no_pad_migration_aborted_previous_migration_not_applied');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- The target collation has to exist on this server before the ALTER references it.
SET @collation_available := (
    SELECT COUNT(*) FROM information_schema.COLLATIONS
     WHERE collation_name = 'utf8mb4_0900_bin'
);
SET @gate := IF(@collation_available = 1,
    'SELECT 1',
    'SELECT 1 FROM no_pad_migration_aborted_utf8mb4_0900_bin_unavailable_on_this_server');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- the change ------------------------------------------------------------
SET @needs_change := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records'
       AND column_name = 'external_job_id'
       AND collation_name <> 'utf8mb4_0900_bin'
);
SET @alter := IF(@needs_change = 1,
    'ALTER TABLE job_source_records
        MODIFY external_job_id VARCHAR(190)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- verification ----------------------------------------------------------
SET @collation := (
    SELECT collation_name FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records' AND column_name = 'external_job_id'
);
-- The property that actually matters, read from the server rather than inferred from the
-- collation's name.
SET @pad := (
    SELECT pad_attribute FROM information_schema.COLLATIONS
     WHERE collation_name = @collation
);
SET @length := (
    SELECT character_maximum_length FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records' AND column_name = 'external_job_id'
);
SET @nullable := (
    SELECT is_nullable FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records' AND column_name = 'external_job_id'
);
SET @unique_columns := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records'
       AND index_name = 'uk_job_source_records_source_external'
       AND ((seq_in_index = 1 AND column_name = 'source_id')
         OR (seq_in_index = 2 AND column_name = 'external_job_id'))
);
SET @unique_is_unique := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records'
       AND index_name = 'uk_job_source_records_source_external'
       AND non_unique = 0 AND seq_in_index = 1
);
SET @text_untouched := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_catalog'
       AND column_name IN ('title', 'company_name', 'description_text')
       AND collation_name = 'utf8mb4_unicode_ci'
);
SET @fulltext_intact := (
    SELECT COUNT(DISTINCT index_name) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_catalog'
       AND index_name = 'ft_job_catalog_text'
);

SELECT @collation AS external_job_id_collation,
       @pad AS external_job_id_pad_attribute,
       @length AS external_job_id_length,
       @nullable AS external_job_id_nullable,
       @unique_columns AS unique_key_columns_of_2,
       @unique_is_unique AS unique_key_is_unique_of_1,
       @text_untouched AS human_text_columns_still_ci_of_3,
       @fulltext_intact AS fulltext_index_of_1;

SET @gate := IF(@collation = 'utf8mb4_0900_bin'
                AND @pad = 'NO PAD'
                AND @length = 190
                AND @nullable = 'NO'
                AND @unique_columns = 2
                AND @unique_is_unique = 1
                AND @text_untouched = 3
                AND @fulltext_intact = 1,
    'SELECT 1',
    'SELECT 1 FROM no_pad_migration_aborted_structure_wrong_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-01-job-source-records-external-id-no-pad');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
