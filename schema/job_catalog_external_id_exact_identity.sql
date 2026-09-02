-- ---------------------------------------------------------------------------
-- Make a posting's external id an EXACT identifier.
--
-- Version: 2026-09-01-job-source-records-external-id-binary
-- Requires: 2026-08-31-job-catalog-phase1
--
-- The defect
-- ----------
-- job_source_records.external_job_id inherited the table's utf8mb4_unicode_ci, which is
-- case-INSENSITIVE. That collation governs both the lookup
-- (JobSourceRecordMapper.findBySourceAndExternalId) and the unique key
-- uk_job_source_records_source_external, so on the same source a board's "REQ-A" and
-- "req-a" resolved to one row: the second posting was folded into the first and
-- overwrote it. Confirmed on MySQL 8.4 -- two distinct postings imported, one row stored.
--
-- H2 did not catch this. Its VARCHAR comparison is case-sensitive, so the same test
-- passes there for a reason that has nothing to do with production.
--
-- The fix
-- -------
-- utf8mb4_bin on that one column. An ATS requisition id is an opaque token from another
-- system, not human-readable text: two ids that differ by a byte are two ids, and no
-- language-aware folding should have a say in it. Because a unique index uses its
-- column's collation, this single change fixes the lookup and the constraint together --
-- changing only the WHERE clause would leave the index still merging them on insert.
--
-- Not utf8mb4_0900_as_cs: that is case- and accent-sensitive but still applies Unicode
-- weighting. For an opaque identifier the intended comparison is over the bytes the
-- source gave us.
--
-- Scope
-- -----
-- One column. The database's collation is untouched, and so are job_catalog.title,
-- company_name, description_text and the ft_job_catalog_text full-text index -- those
-- ARE human text, where case-insensitive matching is the desired search behaviour. The
-- column keeps VARCHAR(190) and NOT NULL.
--
-- Direction of travel is safe: case-insensitive to case-sensitive can only SPLIT keys
-- that previously collided, never create a new collision, so the ALTER cannot fail on
-- duplicate rows.
--
-- What this does NOT do
-- ---------------------
-- It does not recover data already lost. Where two requisitions were merged before this
-- ran, the row that was overwritten is gone and only a fresh collection from the source
-- can bring it back. This migration stops it happening again; it does not undo it.
--
-- Re-runnable: the ALTER is skipped when the column is already utf8mb4_bin, and the
-- completion marker is INSERT IGNORE.
-- ---------------------------------------------------------------------------

-- --- preconditions ---------------------------------------------------------
-- Phase 1 must have been applied. Running this against a database that never had the
-- catalog tables would otherwise report a confusing missing-table error.
SET @phase1 := (
    SELECT COUNT(*) FROM schema_migrations
     WHERE version = '2026-08-31-job-catalog-phase1'
);
SET @gate := IF(@phase1 = 1,
    'SELECT 1',
    'SELECT 1 FROM external_id_migration_aborted_phase1_not_applied');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- the change ------------------------------------------------------------
-- Guarded so a second run is a no-op rather than a needless table rebuild.
-- "Not yet at least this strict", not "not equal to utf8mb4_bin". A later migration in
-- this chain moves the column on to utf8mb4_0900_bin, which is strictly stronger (it adds
-- NO PAD). An equality test here would drag it back down every time the chain is re-run,
-- silently undoing the newer fix -- so this one treats an already-stronger collation as
-- satisfying it and leaves the column alone.
SET @needs_change := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records'
       AND column_name = 'external_job_id'
       AND collation_name NOT IN ('utf8mb4_bin', 'utf8mb4_0900_bin')
);
SET @alter := IF(@needs_change = 1,
    'ALTER TABLE job_source_records
        MODIFY external_job_id VARCHAR(190)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- verification ----------------------------------------------------------
-- The column is now exact, and everything about it that was not meant to change did
-- not. A migration that reports success without reading back what it did is a migration
-- that can record completion over a half-applied schema.
SET @collation := (
    SELECT collation_name FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records' AND column_name = 'external_job_id'
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
-- The unique key must have survived the rebuild, over the same two columns in the same
-- order. It is the constraint that does the real de-duplication work.
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
-- Human text must NOT have been dragged along. These stay case-insensitive on purpose:
-- a search for "engineer" should find "Engineer".
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
       @length AS external_job_id_length,
       @nullable AS external_job_id_nullable,
       @unique_columns AS unique_key_columns_of_2,
       @unique_is_unique AS unique_key_is_unique_of_1,
       @text_untouched AS human_text_columns_still_ci_of_3,
       @fulltext_intact AS fulltext_index_of_1;

-- Aborts on any of the above being wrong. `mysql` stops at the first error in batch
-- mode, so the marker below is unreachable from a partially applied state.
SET @gate := IF(@collation IN ('utf8mb4_bin', 'utf8mb4_0900_bin')
                AND @length = 190
                AND @nullable = 'NO'
                AND @unique_columns = 2
                AND @unique_is_unique = 1
                AND @text_untouched = 3
                AND @fulltext_intact = 1,
    'SELECT 1',
    'SELECT 1 FROM external_id_migration_aborted_structure_wrong_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Only now is the migration allowed to call itself done.
INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-01-job-source-records-external-id-binary');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
