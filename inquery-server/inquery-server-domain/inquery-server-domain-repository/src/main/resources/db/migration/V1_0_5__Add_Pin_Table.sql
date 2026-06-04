-- Pin table for favoriting tables
CREATE TABLE IF NOT EXISTS pin_table (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(128) DEFAULT NULL,
    schema_name VARCHAR(128) DEFAULT NULL,
    table_name VARCHAR(128) DEFAULT NULL,
    deleted TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pin_table IS 'Pinned tables';
COMMENT ON COLUMN pin_table.id IS 'Primary key';
COMMENT ON COLUMN pin_table.gmt_create IS 'Creation time';
COMMENT ON COLUMN pin_table.gmt_modified IS 'Modified time';
COMMENT ON COLUMN pin_table.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN pin_table.database_name IS 'Database name';
COMMENT ON COLUMN pin_table.schema_name IS 'Schema name';
COMMENT ON COLUMN pin_table.table_name IS 'Table name';
COMMENT ON COLUMN pin_table.deleted IS 'Deleted status (y/n)';
COMMENT ON COLUMN pin_table.user_id IS 'User ID';

CREATE INDEX idx_user_id_data_source_id ON pin_table(user_id, data_source_id);
