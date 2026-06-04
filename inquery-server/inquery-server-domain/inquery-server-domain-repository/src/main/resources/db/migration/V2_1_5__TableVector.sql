-- Table vector mapping table
CREATE TABLE IF NOT EXISTS table_vector_mapping (
    id BIGSERIAL NOT NULL,
    api_key VARCHAR(512) DEFAULT NULL,
    data_source_id BIGINT DEFAULT NULL,
    database TEXT DEFAULT NULL,
    schema TEXT DEFAULT NULL,
    status VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE table_vector_mapping IS 'Vector mapping table records';
COMMENT ON COLUMN table_vector_mapping.id IS 'Primary key';
COMMENT ON COLUMN table_vector_mapping.api_key IS 'API key';
COMMENT ON COLUMN table_vector_mapping.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN table_vector_mapping.database IS 'Database name';
COMMENT ON COLUMN table_vector_mapping.schema IS 'Schema name';
COMMENT ON COLUMN table_vector_mapping.status IS 'Vector save status';

CREATE INDEX idx_api_key ON table_vector_mapping(api_key);
