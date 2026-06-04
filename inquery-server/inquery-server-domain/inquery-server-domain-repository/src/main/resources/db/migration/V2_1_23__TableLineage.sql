-- Table lineage information for mart tables
-- Stores the source query and related metadata

CREATE TABLE IF NOT EXISTS table_lineage (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(256) NULL,
    schema_name VARCHAR(256) NULL,
    table_name VARCHAR(256) NOT NULL,
    source_query TEXT NULL,
    source_tables VARCHAR(2048) NULL,
    description VARCHAR(1024) NULL,
    user_id BIGINT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE table_lineage IS 'Table lineage information';
COMMENT ON COLUMN table_lineage.id IS 'Primary key';
COMMENT ON COLUMN table_lineage.gmt_create IS 'Creation time';
COMMENT ON COLUMN table_lineage.gmt_modified IS 'Modified time';
COMMENT ON COLUMN table_lineage.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN table_lineage.database_name IS 'Database name';
COMMENT ON COLUMN table_lineage.schema_name IS 'Schema name';
COMMENT ON COLUMN table_lineage.table_name IS 'Target table name';
COMMENT ON COLUMN table_lineage.source_query IS 'Source query that creates/populates this table';
COMMENT ON COLUMN table_lineage.source_tables IS 'Comma-separated list of source table names';
COMMENT ON COLUMN table_lineage.description IS 'User description of lineage';
COMMENT ON COLUMN table_lineage.user_id IS 'User ID who created/modified';

CREATE INDEX idx_lineage_table ON table_lineage(data_source_id, database_name, schema_name, table_name);
