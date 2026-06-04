-- Schema metadata cache tables for caching database schema information

-- Schema cache (top level)
CREATE TABLE IF NOT EXISTS schema_meta_cache (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    schema_name VARCHAR(255) DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE (data_source_id, database_name, schema_name, user_id)
);
COMMENT ON TABLE schema_meta_cache IS 'Schema metadata cache';
COMMENT ON COLUMN schema_meta_cache.id IS 'Primary key';
COMMENT ON COLUMN schema_meta_cache.gmt_create IS 'Creation time';
COMMENT ON COLUMN schema_meta_cache.gmt_modified IS 'Modified time';
COMMENT ON COLUMN schema_meta_cache.data_source_id IS 'Data source ID';
COMMENT ON COLUMN schema_meta_cache.database_name IS 'Database name';
COMMENT ON COLUMN schema_meta_cache.schema_name IS 'Schema name';
COMMENT ON COLUMN schema_meta_cache.user_id IS 'User ID';

-- Table cache
CREATE TABLE IF NOT EXISTS table_meta_cache (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    schema_cache_id BIGINT NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    table_type VARCHAR(50) DEFAULT NULL,
    comment TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE table_meta_cache IS 'Table metadata cache';
COMMENT ON COLUMN table_meta_cache.id IS 'Primary key';
COMMENT ON COLUMN table_meta_cache.gmt_create IS 'Creation time';
COMMENT ON COLUMN table_meta_cache.gmt_modified IS 'Modified time';
COMMENT ON COLUMN table_meta_cache.schema_cache_id IS 'Schema cache ID';
COMMENT ON COLUMN table_meta_cache.table_name IS 'Table name';
COMMENT ON COLUMN table_meta_cache.table_type IS 'Table type (BASE TABLE, VIEW, etc.)';
COMMENT ON COLUMN table_meta_cache.comment IS 'Table comment';

CREATE INDEX idx_schema_cache_id ON table_meta_cache(schema_cache_id);

-- Column cache
CREATE TABLE IF NOT EXISTS column_meta_cache (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    table_cache_id BIGINT NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    data_type VARCHAR(2048) DEFAULT NULL,
    is_primary_key SMALLINT DEFAULT 0,
    is_nullable SMALLINT DEFAULT 1,
    comment TEXT DEFAULT NULL,
    ordinal_position INTEGER DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE column_meta_cache IS 'Column metadata cache';
COMMENT ON COLUMN column_meta_cache.id IS 'Primary key';
COMMENT ON COLUMN column_meta_cache.gmt_create IS 'Creation time';
COMMENT ON COLUMN column_meta_cache.gmt_modified IS 'Modified time';
COMMENT ON COLUMN column_meta_cache.table_cache_id IS 'Table cache ID';
COMMENT ON COLUMN column_meta_cache.column_name IS 'Column name';
COMMENT ON COLUMN column_meta_cache.data_type IS 'Data type';
COMMENT ON COLUMN column_meta_cache.is_primary_key IS 'Is primary key';
COMMENT ON COLUMN column_meta_cache.is_nullable IS 'Is nullable';
COMMENT ON COLUMN column_meta_cache.comment IS 'Column comment';
COMMENT ON COLUMN column_meta_cache.ordinal_position IS 'Column ordinal position';

CREATE INDEX idx_table_cache_id ON column_meta_cache(table_cache_id);
