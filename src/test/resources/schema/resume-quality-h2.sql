-- H2 (MySQL mode) equivalent of the `users` + `resumes` tables, for the resume
-- quality-score endpoint's real Spring Security + MockMvc integration test. Semantics
-- match infra/aws/mysql-init/01-schema.sql; MySQL-only bits (JSON column type,
-- ON UPDATE CURRENT_TIMESTAMP, foreign keys) are dropped because H2 rejects some of
-- them and this test never needs them -- same idiom as schema/job-catalog-h2.sql.

DROP TABLE IF EXISTS resumes;
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

CREATE TABLE resumes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    extracted_text CLOB,
    parent_resume_id BIGINT NULL,
    canonical_resume_id BIGINT NULL,
    origin VARCHAR(32) NOT NULL DEFAULT 'UPLOAD',
    review_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    structured_json CLOB NULL,
    layout_json CLOB NULL,
    extraction_status VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
