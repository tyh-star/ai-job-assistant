-- ---------------------------------------------------------------------------
-- "网站评价" (site feedback) -- one star rating (1-5) plus an optional plain-text
-- comment, at most one row per user, editable in place.
--
-- Version: 2026-09-07-site-feedback
--
-- What this adds
-- ---------------
-- One new table:
--   site_feedback (id, user_id UNIQUE, rating TINYINT 1-5, comment VARCHAR(1000) NULL,
--                  created_at, updated_at)
--
-- `user_id` is UNIQUE (not the primary key) so the application can look a user's own
-- feedback up directly and update it in place -- see FeedbackServiceImpl, which reads
-- the existing row first and either updates or inserts, never relying on a
-- dialect-specific upsert statement (kept portable to H2 for tests, not just MySQL).
-- `rating` is constrained 1-5 at BOTH the database (CHECK) and the application layer
-- (bean validation) -- defense in depth, since a CHECK constraint alone would only
-- reject the write with a raw SQL error, not the friendly 400 the API returns.
-- `comment` is always treated as plain text end-to-end (see FeedbackController's
-- javadoc) -- never rendered as HTML anywhere, so this column holding arbitrary text
-- is not an XSS surface by itself.
--
-- The public summary endpoint (GET /api/v1/feedback/summary) reads only
-- AVG(rating)/COUNT(*) from this table -- never SELECTs comment, and never joins to
-- users -- so no email, name, or comment text can leak through it even by a future
-- mistake in that one query (see FeedbackMapper#getSummary).
--
-- Re-runnable: run via ./infra/aws/db-apply.sh, chained into the ordered release at
-- infra/aws/release.sh step 7 (after users_auth_version.sql, before the final
-- structure verify). CREATE TABLE IF NOT EXISTS is a no-op on a database that already
-- has this table, so a re-release re-applies this harmlessly, exactly like the other
-- files in this chain.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(128) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS site_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    comment VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_site_feedback_user (user_id),
    CONSTRAINT chk_site_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT fk_site_feedback_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --- verification ------------------------------------------------------

SET @tables := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE()
       AND table_name IN ('schema_migrations', 'site_feedback')
);

SET @columns := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE()
       AND table_name = 'site_feedback'
       AND column_name IN ('user_id', 'rating', 'comment', 'created_at', 'updated_at')
);

SET @unique_key := (
    SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.STATISTICS
     WHERE table_schema = DATABASE()
       AND table_name = 'site_feedback'
       AND index_name = 'uk_site_feedback_user'
);

SELECT @tables AS tables_found_of_2,
       @columns AS columns_found_of_5,
       @unique_key AS unique_key_found_of_1;

SET @gate := IF(@tables = 2 AND @columns = 5 AND @unique_key = 1,
    'SELECT 1',
    'SELECT 1 FROM site_feedback_migration_aborted_structure_incomplete_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-07-site-feedback');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
