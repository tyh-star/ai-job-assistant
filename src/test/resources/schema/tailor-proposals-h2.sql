-- H2 (MODE=MySQL) copy of tailor_proposals for mapper tests; JSON columns as CLOB.
DROP TABLE IF EXISTS tailor_proposals;

CREATE TABLE tailor_proposals (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    changes_json CLOB NOT NULL,
    rejected_json CLOB NOT NULL,
    generated_file_name VARCHAR(255) NULL,
    generated_file_path VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
