-- Table cache version table
CREATE TABLE IF NOT EXISTS table_cache_version (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(256) DEFAULT NULL,
    schema_name VARCHAR(256) DEFAULT NULL,
    key VARCHAR(256) DEFAULT NULL,
    version BIGINT NOT NULL,
    table_count BIGINT NOT NULL,
    status VARCHAR(256) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE table_cache_version IS 'Table cache version';
COMMENT ON COLUMN table_cache_version.id IS 'Primary key';
COMMENT ON COLUMN table_cache_version.gmt_create IS 'Creation time';
COMMENT ON COLUMN table_cache_version.gmt_modified IS 'Modified time';
COMMENT ON COLUMN table_cache_version.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN table_cache_version.database_name IS 'Database name';
COMMENT ON COLUMN table_cache_version.schema_name IS 'Schema name';
COMMENT ON COLUMN table_cache_version.key IS 'Unique key';
COMMENT ON COLUMN table_cache_version.version IS 'Version';
COMMENT ON COLUMN table_cache_version.table_count IS 'Table count';
COMMENT ON COLUMN table_cache_version.status IS 'Status';

CREATE INDEX idx_table_cache_version_data_source_id ON table_cache_version(data_source_id);
CREATE UNIQUE INDEX uk_table_cache_version_key ON table_cache_version(key);

-- Table cache table
CREATE TABLE IF NOT EXISTS table_cache (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(256) DEFAULT NULL,
    schema_name VARCHAR(256) DEFAULT NULL,
    table_name VARCHAR(256) DEFAULT NULL,
    key VARCHAR(256) DEFAULT NULL,
    version BIGINT NOT NULL,
    columns TEXT DEFAULT NULL,
    extend_info TEXT DEFAULT NULL,
    table_type VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE table_cache IS 'Table cache';
COMMENT ON COLUMN table_cache.id IS 'Primary key';
COMMENT ON COLUMN table_cache.gmt_create IS 'Creation time';
COMMENT ON COLUMN table_cache.gmt_modified IS 'Modified time';
COMMENT ON COLUMN table_cache.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN table_cache.database_name IS 'Database name';
COMMENT ON COLUMN table_cache.schema_name IS 'Schema name';
COMMENT ON COLUMN table_cache.table_name IS 'Table name';
COMMENT ON COLUMN table_cache.key IS 'Unique key';
COMMENT ON COLUMN table_cache.version IS 'Version';
COMMENT ON COLUMN table_cache.columns IS 'Table columns';
COMMENT ON COLUMN table_cache.extend_info IS 'Custom extension fields JSON';
COMMENT ON COLUMN table_cache.table_type IS 'Table type (TABLE, VIEW, etc.)';

CREATE INDEX idx_table_cache_data_source_id ON table_cache(data_source_id);
CREATE INDEX idx_table_cache_key_version ON table_cache(key, version);
CREATE INDEX idx_table_cache_key_table_name ON table_cache(key, table_name);
CREATE INDEX idx_table_cache_table_type ON table_cache(table_type);
