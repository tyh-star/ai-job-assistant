-- Structure verification for the Phase 1 job catalog. READ-ONLY: it creates nothing,
-- changes nothing, and deletes nothing, so it is safe to run against production at any
-- time.
--
-- `schema/job_catalog_phase1.sql` runs this same gate inline before it records itself
-- as applied. This file exists so the check can also be run on its own -- before a
-- deploy, after a restore, or to confirm a database that someone else migrated.
--
-- Exit status is what a deploy script should branch on: 0 means the structure is
-- complete, non-zero means it is not. On failure the counts printed above the error
-- say which category is short.

SELECT table_name AS present_table
  FROM information_schema.TABLES
 WHERE table_schema = DATABASE()
   AND table_name IN ('schema_migrations', 'job_sources', 'job_catalog',
                      'job_source_records', 'job_ingest_runs', 'user_saved_catalog_jobs')
 ORDER BY table_name;

SET @tables := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE()
       AND table_name IN ('schema_migrations', 'job_sources', 'job_catalog',
                          'job_source_records', 'job_ingest_runs', 'user_saved_catalog_jobs')
);

SET @columns := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND (
            (table_name = 'job_sources' AND column_name IN
                ('provider_type', 'tenant_key', 'enabled', 'crawl_allowed',
                 'collection_interval_minutes', 'next_run_at', 'consecutive_failures',
                 'locked_by', 'locked_at'))
         OR (table_name = 'job_catalog' AND column_name IN
                ('title', 'company_name', 'description_text', 'status', 'fingerprint',
                 'canonical_url', 'apply_url', 'canonical_url_norm', 'apply_url_norm',
                 'first_seen_at', 'last_seen_at'))
         OR (table_name = 'job_source_records' AND column_name IN
                ('source_id', 'external_job_id', 'catalog_job_id', 'payload_hash',
                 'raw_payload', 'last_seen_at', 'active'))
         OR (table_name = 'job_ingest_runs' AND column_name IN
                ('source_id', 'status', 'fetched_count', 'inserted_count',
                 'updated_count', 'unchanged_count', 'expired_count', 'failed_count',
                 'error_summary'))
         OR (table_name = 'user_saved_catalog_jobs' AND column_name IN
                ('user_id', 'catalog_job_id', 'personal_job_id'))
       )
);

SET @indexes := (
    SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND index_name IN ('uk_job_sources_provider_tenant', 'idx_job_sources_due',
                          'ft_job_catalog_text', 'idx_job_catalog_apply_url_norm',
                          'idx_job_catalog_canonical_url_norm', 'idx_job_catalog_fingerprint',
                          'idx_job_catalog_status_posted',
                          'uk_job_source_records_source_external',
                          'idx_job_source_records_catalog',
                          'uk_user_saved_catalog_jobs_user_catalog')
);

SET @foreign_keys := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE table_schema = DATABASE()
       AND constraint_type = 'FOREIGN KEY'
       AND constraint_name IN ('fk_job_source_records_source', 'fk_job_source_records_catalog',
                               'fk_job_ingest_runs_source', 'fk_user_saved_catalog_jobs_user',
                               'fk_user_saved_catalog_jobs_catalog',
                               'fk_user_saved_catalog_jobs_personal')
);

-- A posting's external id must compare exactly. Counted here and not only in the
-- migration that sets it, because the failure this catches is a release that applied
-- phase 1 and skipped the ALTER: every table, column, index and key would be present and
-- the structure would still silently merge "REQ-A" with "req-a" -- or, on utf8mb4_bin,
-- with "REQ-A ", since that collation is PAD SPACE.
SET @external_id_exact := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_source_records'
       AND column_name = 'external_job_id'
       AND collation_name = 'utf8mb4_0900_bin'
);

SELECT @tables AS tables_found_of_6,
       @columns AS columns_found_of_39,
       @indexes AS indexes_found_of_10,
       @foreign_keys AS foreign_keys_found_of_6,
       @external_id_exact AS external_job_id_binary_no_pad_of_1;

-- Fails the script (and the shell) when anything is missing. Deliberately before the
-- schema_migrations read: on a database that never got the migration that table does
-- not exist, and the counts above are the useful diagnostic, not a missing-table error.
SET @gate := IF(@tables = 6 AND @columns = 39 AND @indexes = 10 AND @foreign_keys = 6
                AND @external_id_exact = 1,
    'SELECT 1',
    'SELECT 1 FROM job_catalog_structure_incomplete_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;

SELECT 'job catalog structure verified' AS result;
