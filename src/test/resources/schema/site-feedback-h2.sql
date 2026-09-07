-- H2 (MySQL mode) equivalent of the `users` + `site_feedback` tables, for the site
-- feedback endpoints' real Spring Security + MockMvc integration test. Semantics match
-- infra/aws/mysql-init/01-schema.sql; MySQL-only bits (ON UPDATE CURRENT_TIMESTAMP,
-- CHECK enforcement nuances, foreign keys) are dropped/simplified because H2 differs
-- slightly and this test never needs them -- same idiom as the other *-h2.sql files.

DROP TABLE IF EXISTS site_feedback;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'USER',
    -- Read by JwtAuthenticationFilter on every authenticated request -- without this
    -- column every request in this test would 401 regardless of what it is testing.
    auth_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE site_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    rating TINYINT NOT NULL,
    comment VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
