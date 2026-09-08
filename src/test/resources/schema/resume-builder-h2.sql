-- H2 (MySQL mode) equivalent of the `users` + `resumes` + `resume_builder_drafts` +
-- `resume_builder_generation_requests` tables, for the resume builder endpoints' real
-- Spring Security + MockMvc integration tests. Semantics match
-- infra/aws/mysql-init/01-schema.sql; MySQL-only bits (ON UPDATE CURRENT_TIMESTAMP,
-- foreign keys) are dropped/simplified because H2 differs slightly and these tests
-- never need them -- same idiom as the other *-h2.sql files.

DROP TABLE IF EXISTS resume_builder_generation_requests;
DROP TABLE IF EXISTS resume_builder_drafts;
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

CREATE TABLE resume_builder_drafts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    template_type VARCHAR(40) NOT NULL,
    target_role VARCHAR(200) NULL,
    locale VARCHAR(10) NOT NULL DEFAULT 'en',
    schema_version INT NOT NULL DEFAULT 1,
    draft_json CLOB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resume_builder_generation_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    draft_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    resume_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_resume_builder_gen_user_key UNIQUE (user_id, idempotency_key)
);
