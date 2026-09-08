-- ---------------------------------------------------------------------------
-- Job search history -- typed columns for a logged-in user's explicit catalog
-- searches (Search click / Enter after a successful request). Page number is
-- never stored. Anonymous history stays in the browser and is never merged.
--
-- Version: 2026-09-07-job-search-history
--
-- UNIQUE(user_id, criteria_hash) is what makes a repeated identical search
-- increment search_count and last_searched_at instead of inserting a second
-- row. The application trims each user to the 20 newest rows after an upsert.
-- ON DELETE CASCADE on user_id clears history when the account is deleted.
--
-- Re-runnable: CREATE TABLE IF NOT EXISTS is a no-op on a database that
-- already has this table. Chained into infra/aws/release.sh step 7 after
-- resume_builder.sql, before the final structure verify.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(128) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_search_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    keywords VARCHAR(500) NULL,
    city VARCHAR(190) NULL,
    city_country VARCHAR(8) NULL,
    country VARCHAR(8) NULL,
    work_mode VARCHAR(16) NULL,
    employment_type VARCHAR(24) NULL,
    posted_within INT NULL,
    sort_by VARCHAR(32) NULL,
    criteria_hash CHAR(64) NOT NULL,
    search_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_searched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_search_history_user_criteria (user_id, criteria_hash),
    KEY idx_job_search_history_user (user_id),
    KEY idx_job_search_history_last_searched (last_searched_at),
    CONSTRAINT fk_job_search_history_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SELECT table_name AS present_table
  FROM information_schema.TABLES
 WHERE table_schema = DATABASE()
   AND table_name IN ('schema_migrations', 'job_search_history')
 ORDER BY table_name;

SET @tables := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE()
       AND table_name IN ('schema_migrations', 'job_search_history')
);

SET @columns := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'job_search_history'
       AND column_name IN
           ('user_id', 'keywords', 'city', 'city_country', 'country', 'work_mode',
            'employment_type', 'posted_within', 'sort_by', 'criteria_hash',
            'search_count', 'created_at', 'last_searched_at')
);

SET @indexes := (
    SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND index_name IN ('uk_job_search_history_user_criteria',
                          'idx_job_search_history_user',
                          'idx_job_search_history_last_searched')
);

SET @foreign_keys := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE table_schema = DATABASE()
       AND constraint_type = 'FOREIGN KEY'
       AND constraint_name = 'fk_job_search_history_user'
);

SELECT @tables AS tables_found_of_2,
       @columns AS columns_found_of_13,
       @indexes AS indexes_found_of_3,
       @foreign_keys AS foreign_keys_found_of_1;

SET @gate := IF(@tables = 2 AND @columns = 13 AND @indexes = 3 AND @foreign_keys = 1,
    'SELECT 1',
    'SELECT 1 FROM job_search_history_migration_aborted_structure_incomplete_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-07-job-search-history');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
