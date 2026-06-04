-- Task table
CREATE TABLE IF NOT EXISTS task (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_source_id BIGINT NULL,
    database_name VARCHAR(128) DEFAULT NULL,
    schema_name VARCHAR(128) DEFAULT NULL,
    table_name VARCHAR(128) DEFAULT NULL,
    deleted VARCHAR(10) DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    task_type VARCHAR(128) DEFAULT NULL,
    task_status VARCHAR(128) DEFAULT NULL,
    task_progress VARCHAR(128) DEFAULT NULL,
    task_name VARCHAR(128) DEFAULT NULL,
    content BYTEA DEFAULT NULL,
    download_url VARCHAR(512) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE task IS 'Task table';
COMMENT ON COLUMN task.id IS 'Primary key';
COMMENT ON COLUMN task.gmt_create IS 'Creation time';
COMMENT ON COLUMN task.gmt_modified IS 'Modified time';
COMMENT ON COLUMN task.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN task.database_name IS 'Database name';
COMMENT ON COLUMN task.schema_name IS 'Schema name';
COMMENT ON COLUMN task.table_name IS 'Table name';
COMMENT ON COLUMN task.deleted IS 'Deleted status (y/n)';
COMMENT ON COLUMN task.user_id IS 'User ID';
COMMENT ON COLUMN task.task_type IS 'Task type (DOWNLOAD_DATA, UPLOAD_TABLE_DATA, etc.)';
COMMENT ON COLUMN task.task_status IS 'Task status';
COMMENT ON COLUMN task.task_progress IS 'Task progress';
COMMENT ON COLUMN task.task_name IS 'Task name';
COMMENT ON COLUMN task.content IS 'Task content';
COMMENT ON COLUMN task.download_url IS 'Download URL';

CREATE INDEX idx_task_user_id ON task(user_id);
