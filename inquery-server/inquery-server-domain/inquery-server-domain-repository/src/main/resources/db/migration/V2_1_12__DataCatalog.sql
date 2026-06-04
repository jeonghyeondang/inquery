-- Data catalog table
CREATE TABLE IF NOT EXISTS data_catalog_table (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(256) DEFAULT NULL,
    schema_name VARCHAR(256) DEFAULT NULL,
    table_name VARCHAR(256) NOT NULL,
    table_description TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    active SMALLINT DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE (data_source_id, database_name, schema_name, table_name)
);
COMMENT ON TABLE data_catalog_table IS 'Data catalog table information';
COMMENT ON COLUMN data_catalog_table.id IS 'Primary key';
COMMENT ON COLUMN data_catalog_table.gmt_create IS 'Creation time';
COMMENT ON COLUMN data_catalog_table.gmt_modified IS 'Modified time';
COMMENT ON COLUMN data_catalog_table.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN data_catalog_table.database_name IS 'Database name';
COMMENT ON COLUMN data_catalog_table.schema_name IS 'Schema name';
COMMENT ON COLUMN data_catalog_table.table_name IS 'Table name';
COMMENT ON COLUMN data_catalog_table.table_description IS 'Table description';
COMMENT ON COLUMN data_catalog_table.user_id IS 'User ID';
COMMENT ON COLUMN data_catalog_table.active IS 'Whether this table is active for AI queries (1=active, 0=inactive)';

CREATE INDEX idx_data_catalog_table_data_source_id ON data_catalog_table(data_source_id);
CREATE INDEX idx_data_catalog_table_database_name ON data_catalog_table(database_name);
CREATE INDEX idx_data_catalog_table_table_name ON data_catalog_table(table_name);
CREATE INDEX idx_data_catalog_table_active ON data_catalog_table(active);

-- Data catalog column table
CREATE TABLE IF NOT EXISTS data_catalog_column (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    table_id BIGINT NOT NULL,
    column_name VARCHAR(256) NOT NULL,
    column_description TEXT DEFAULT NULL,
    schema_info TEXT DEFAULT NULL,
    example_values TEXT DEFAULT NULL,
    ordinal_position INTEGER DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (table_id, column_name)
);
COMMENT ON TABLE data_catalog_column IS 'Data catalog column information';
COMMENT ON COLUMN data_catalog_column.id IS 'Primary key';
COMMENT ON COLUMN data_catalog_column.gmt_create IS 'Creation time';
COMMENT ON COLUMN data_catalog_column.gmt_modified IS 'Modified time';
COMMENT ON COLUMN data_catalog_column.table_id IS 'Reference to data_catalog_table.id';
COMMENT ON COLUMN data_catalog_column.column_name IS 'Column name';
COMMENT ON COLUMN data_catalog_column.column_description IS 'Column description';
COMMENT ON COLUMN data_catalog_column.schema_info IS 'Schema information (JSON format)';
COMMENT ON COLUMN data_catalog_column.example_values IS 'Example values (JSON array)';
COMMENT ON COLUMN data_catalog_column.ordinal_position IS 'Column ordinal position (DDL order)';

CREATE INDEX idx_data_catalog_column_table_id ON data_catalog_column(table_id);
