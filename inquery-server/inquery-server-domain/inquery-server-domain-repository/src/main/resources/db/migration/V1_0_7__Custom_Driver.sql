-- Custom JDBC driver table
CREATE TABLE IF NOT EXISTS jdbc_driver (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    db_type VARCHAR(32) NOT NULL,
    jdbc_driver VARCHAR(512) DEFAULT NULL,
    jdbc_driver_class VARCHAR(512) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE jdbc_driver IS 'Custom JDBC driver table';
COMMENT ON COLUMN jdbc_driver.id IS 'Primary key';
COMMENT ON COLUMN jdbc_driver.gmt_create IS 'Creation time';
COMMENT ON COLUMN jdbc_driver.gmt_modified IS 'Modified time';
COMMENT ON COLUMN jdbc_driver.db_type IS 'Database type';
COMMENT ON COLUMN jdbc_driver.jdbc_driver IS 'JAR file path';
COMMENT ON COLUMN jdbc_driver.jdbc_driver_class IS 'Driver class name';

CREATE INDEX idx_db_type ON jdbc_driver(db_type);

ALTER TABLE data_source ADD COLUMN driver_config VARCHAR(8192) NULL;
COMMENT ON COLUMN data_source.driver_config IS 'Driver configuration';
