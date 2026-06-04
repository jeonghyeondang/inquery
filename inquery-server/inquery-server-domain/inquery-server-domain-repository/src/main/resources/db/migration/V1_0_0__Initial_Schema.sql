-- Data source connection table
CREATE TABLE IF NOT EXISTS data_source (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL,
    gmt_modified TIMESTAMP NOT NULL,
    alias VARCHAR(128) DEFAULT NULL,
    url VARCHAR(1024) DEFAULT NULL,
    user_name VARCHAR(128) DEFAULT NULL,
    password VARCHAR(256) DEFAULT NULL,
    type VARCHAR(32) DEFAULT NULL,
    env_type VARCHAR(32) DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    host VARCHAR(128) DEFAULT NULL,
    port VARCHAR(128) DEFAULT NULL,
    ssh VARCHAR(1024) DEFAULT NULL,
    ssl VARCHAR(1024) DEFAULT NULL,
    sid VARCHAR(32) DEFAULT NULL,
    driver VARCHAR(128) DEFAULT NULL,
    jdbc VARCHAR(128) DEFAULT NULL,
    extend_info VARCHAR(4096) DEFAULT NULL,
    service_name VARCHAR(128) DEFAULT NULL,
    service_type VARCHAR(128) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE data_source IS 'Data source connection table';
COMMENT ON COLUMN data_source.id IS 'Primary key';
COMMENT ON COLUMN data_source.gmt_create IS 'Creation time';
COMMENT ON COLUMN data_source.gmt_modified IS 'Modified time';
COMMENT ON COLUMN data_source.alias IS 'Alias';
COMMENT ON COLUMN data_source.url IS 'Connection URL';
COMMENT ON COLUMN data_source.user_name IS 'Username';
COMMENT ON COLUMN data_source.password IS 'Password';
COMMENT ON COLUMN data_source.type IS 'Database type';
COMMENT ON COLUMN data_source.env_type IS 'Environment type';
COMMENT ON COLUMN data_source.user_id IS 'User ID';
COMMENT ON COLUMN data_source.host IS 'Host address';
COMMENT ON COLUMN data_source.port IS 'Port';
COMMENT ON COLUMN data_source.ssh IS 'SSH configuration JSON';
COMMENT ON COLUMN data_source.ssl IS 'SSL configuration JSON';
COMMENT ON COLUMN data_source.sid IS 'SID';
COMMENT ON COLUMN data_source.driver IS 'Driver information';
COMMENT ON COLUMN data_source.jdbc IS 'JDBC version';
COMMENT ON COLUMN data_source.extend_info IS 'Custom extension fields JSON';
COMMENT ON COLUMN data_source.service_name IS 'Service name';
COMMENT ON COLUMN data_source.service_type IS 'Service type';
CREATE INDEX idx_user_id ON data_source(user_id);

-- Operation log table
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(128) DEFAULT NULL,
    type VARCHAR(32) NOT NULL,
    ddl TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) DEFAULT 'success',
    operation_rows BIGINT DEFAULT NULL,
    use_time BIGINT DEFAULT NULL,
    extend_info VARCHAR(1024) DEFAULT NULL,
    schema_name VARCHAR(256) DEFAULT NULL,
    source VARCHAR(32) DEFAULT 'WORKSPACE',
    PRIMARY KEY (id)
);
COMMENT ON TABLE operation_log IS 'Execution log table';
COMMENT ON COLUMN operation_log.id IS 'Primary key';
COMMENT ON COLUMN operation_log.gmt_create IS 'Creation time';
COMMENT ON COLUMN operation_log.gmt_modified IS 'Modified time';
COMMENT ON COLUMN operation_log.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN operation_log.database_name IS 'Database name';
COMMENT ON COLUMN operation_log.type IS 'Database type';
COMMENT ON COLUMN operation_log.ddl IS 'DDL content';
COMMENT ON COLUMN operation_log.user_id IS 'User ID';
COMMENT ON COLUMN operation_log.status IS 'Status';
COMMENT ON COLUMN operation_log.operation_rows IS 'Operation rows count';
COMMENT ON COLUMN operation_log.use_time IS 'Execution duration';
COMMENT ON COLUMN operation_log.extend_info IS 'Extended info';
COMMENT ON COLUMN operation_log.schema_name IS 'Schema name';
COMMENT ON COLUMN operation_log.source IS 'Query source: WORKSPACE or AI_CHAT';
CREATE INDEX idx_op_data_source_id ON operation_log(data_source_id);
CREATE INDEX idx_operation_log_source ON operation_log(source);

-- Saved operations table
CREATE TABLE IF NOT EXISTS operation_saved (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(128) DEFAULT NULL,
    name VARCHAR(128) DEFAULT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    ddl TEXT DEFAULT NULL,
    tab_opened TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    db_schema_name VARCHAR(128) DEFAULT NULL,
    operation_type VARCHAR(1024) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE operation_saved IS 'Saved operations table';
COMMENT ON COLUMN operation_saved.id IS 'Primary key';
COMMENT ON COLUMN operation_saved.gmt_create IS 'Creation time';
COMMENT ON COLUMN operation_saved.gmt_modified IS 'Modified time';
COMMENT ON COLUMN operation_saved.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN operation_saved.database_name IS 'Database name';
COMMENT ON COLUMN operation_saved.name IS 'Save name';
COMMENT ON COLUMN operation_saved.type IS 'Database type';
COMMENT ON COLUMN operation_saved.status IS 'DDL status: DRAFT/RELEASE';
COMMENT ON COLUMN operation_saved.ddl IS 'DDL content';
COMMENT ON COLUMN operation_saved.tab_opened IS 'Tab opened status (y/n)';
COMMENT ON COLUMN operation_saved.user_id IS 'User ID';
COMMENT ON COLUMN operation_saved.db_schema_name IS 'Schema name';
COMMENT ON COLUMN operation_saved.operation_type IS 'Operation type';

-- User table
CREATE TABLE IF NOT EXISTS inquery_user (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_name VARCHAR(32) NOT NULL,
    password VARCHAR(256) DEFAULT NULL,
    nick_name VARCHAR(256) DEFAULT NULL,
    email VARCHAR(256) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE inquery_user IS 'User table';
COMMENT ON COLUMN inquery_user.id IS 'Primary key';
COMMENT ON COLUMN inquery_user.gmt_create IS 'Creation time';
COMMENT ON COLUMN inquery_user.gmt_modified IS 'Modified time';
COMMENT ON COLUMN inquery_user.user_name IS 'Username';
COMMENT ON COLUMN inquery_user.password IS 'Password';
COMMENT ON COLUMN inquery_user.nick_name IS 'Nickname';
COMMENT ON COLUMN inquery_user.email IS 'Email';

-- System configuration table
CREATE TABLE IF NOT EXISTS system_config (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    code VARCHAR(32) NOT NULL,
    content TEXT DEFAULT NULL,
    summary VARCHAR(256) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE system_config IS 'System configuration table';
COMMENT ON COLUMN system_config.id IS 'Primary key';
COMMENT ON COLUMN system_config.gmt_create IS 'Creation time';
COMMENT ON COLUMN system_config.gmt_modified IS 'Modified time';
COMMENT ON COLUMN system_config.code IS 'Configuration code';
COMMENT ON COLUMN system_config.content IS 'Configuration content';
COMMENT ON COLUMN system_config.summary IS 'Configuration description';

CREATE UNIQUE INDEX uk_code ON system_config(code);

INSERT INTO inquery_user (user_name, password, nick_name) VALUES ('admin123', 'admin1234', 'Administrator');
