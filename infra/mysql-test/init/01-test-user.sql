-- Runs once, when the empty volume is initialised.
--
-- The official image's MYSQL_USER/MYSQL_DATABASE variables already create the account
-- and grant it the test database. This file exists to make the SCOPE explicit and
-- narrow rather than inherited, because the integration test refuses to run against an
-- account that can reach anything else — and an assertion is only worth having if the
-- thing it checks was set deliberately.
--
-- No password appears here. The account was created by the entrypoint from
-- MYSQL_PASSWORD; this only adjusts what it may do.

-- The database the entrypoint created, restated so its collation is not left to a
-- server default that might drift. utf8mb4_unicode_ci matches production, and matching
-- it is the point: the case-folding behaviour under test is a property of the collation.
ALTER DATABASE aijob_import_test
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- Exactly one database, and nothing server-wide. USAGE is what remains at the global
-- level: the right to connect and nothing more.
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'aijob_test'@'%';
GRANT ALL PRIVILEGES ON `aijob_import_test`.* TO 'aijob_test'@'%';

-- Deliberately NOT granted: PROCESS, FILE, SUPER, RELOAD, SHUTDOWN, CREATE USER, or any
-- privilege ON *.*. An account holding those could read other schemas' data or metadata,
-- and this one exists only to be pointed at a scratch database by a test.

FLUSH PRIVILEGES;
