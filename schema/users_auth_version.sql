-- ---------------------------------------------------------------------------
-- Persistent JWT revocation for `users` -- a token-version counter.
--
-- Version: 2026-09-06-users-auth-version
--
-- What this adds
-- ---------------
-- One column on `users`:
--   auth_version INT NOT NULL DEFAULT 0
--
-- JwtUtil#generateToken now signs an `authVersion` claim equal to the user's row at
-- issue time. JwtAuthenticationFilter re-reads this column on every authenticated
-- request and rejects (401) any token whose claim no longer matches it -- see that
-- filter's javadoc. UserServiceImpl#changePassword bumps this column by 1 in the same
-- UPDATE statement that changes the password hash (UserMapper#changePasswordAndBumpAuthVersion),
-- so a password change invalidates every token issued before it, on every device, not
-- just the browser that changed it -- unlike clearing one browser's own localStorage,
-- which only ever affected that one browser.
--
-- Re-runnable: run via ./infra/aws/db-apply.sh, chained into the ordered release at
-- infra/aws/release.sh step 7 (after jobs_applied_tracking.sql, before the final
-- structure verify). The ALTER is skipped when the column already exists, and the
-- completion marker is INSERT IGNORE, so a re-release re-applies this harmlessly,
-- exactly like the other files in this chain.
--
-- Not atomic, same as every other file here: MySQL commits DDL implicitly. What this
-- guarantees instead is a structure gate at the end (the column is present with the
-- correct type/nullability/default) and a schema_migrations row written only if that
-- gate passes -- a partial run leaves no completion marker, and running this file
-- again finishes the job.
-- ---------------------------------------------------------------------------

-- --- preconditions ---------------------------------------------------------
SET @users_table_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE() AND table_name = 'users'
);
SET @gate := IF(@users_table_exists = 1,
    'SELECT 1',
    'SELECT 1 FROM auth_version_migration_aborted_users_table_missing');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @schema_migrations_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE table_schema = DATABASE() AND table_name = 'schema_migrations'
);
SET @gate := IF(@schema_migrations_exists = 1,
    'SELECT 1',
    'SELECT 1 FROM auth_version_migration_aborted_schema_migrations_table_missing');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- the change --------------------------------------------------------

SET @needs_auth_version := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'auth_version'
) = 0;
SET @alter := IF(@needs_auth_version,
    'ALTER TABLE users ADD COLUMN auth_version INT NOT NULL DEFAULT 0 AFTER role',
    'SELECT 1');
PREPARE stmt FROM @alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --- verification ------------------------------------------------------

SET @auth_version_type := (
    SELECT data_type FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'auth_version'
);
SET @auth_version_nullable := (
    SELECT is_nullable FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'auth_version'
);
SET @auth_version_default := (
    SELECT column_default FROM information_schema.COLUMNS
     WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'auth_version'
);

SELECT @auth_version_type AS auth_version_data_type,
       @auth_version_nullable AS auth_version_is_nullable,
       @auth_version_default AS auth_version_column_default;

SET @gate := IF(@auth_version_type = 'int' AND @auth_version_nullable = 'NO'
                AND @auth_version_default = '0',
    'SELECT 1',
    'SELECT 1 FROM auth_version_migration_aborted_structure_wrong_see_counts_above');
PREPARE stmt FROM @gate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO schema_migrations (version)
VALUES ('2026-09-06-users-auth-version');

SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
