-- Dashboard table
CREATE TABLE IF NOT EXISTS dashboard (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name VARCHAR(128) DEFAULT NULL,
    description VARCHAR(128) DEFAULT NULL,
    schema TEXT DEFAULT NULL,
    deleted TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    refresh_rule VARCHAR(20) DEFAULT 'NONE',
    PRIMARY KEY (id)
);
COMMENT ON TABLE dashboard IS 'Custom dashboard table';
COMMENT ON COLUMN dashboard.id IS 'Primary key';
COMMENT ON COLUMN dashboard.gmt_create IS 'Creation time';
COMMENT ON COLUMN dashboard.gmt_modified IS 'Modified time';
COMMENT ON COLUMN dashboard.name IS 'Dashboard name';
COMMENT ON COLUMN dashboard.description IS 'Dashboard description';
COMMENT ON COLUMN dashboard.schema IS 'Dashboard layout info';
COMMENT ON COLUMN dashboard.deleted IS 'Deleted status (y/n)';
COMMENT ON COLUMN dashboard.user_id IS 'User ID';
COMMENT ON COLUMN dashboard.refresh_rule IS 'Dashboard refresh rule';

-- Chart table
CREATE TABLE IF NOT EXISTS chart (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    name VARCHAR(128) DEFAULT NULL,
    description VARCHAR(128) DEFAULT NULL,
    schema TEXT DEFAULT NULL,
    data_source_id BIGINT DEFAULT NULL,
    type VARCHAR(32) DEFAULT NULL,
    database_name VARCHAR(128) DEFAULT NULL,
    ddl TEXT DEFAULT NULL,
    deleted TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    schema_name VARCHAR(128) DEFAULT NULL,
    source_type VARCHAR(20) DEFAULT 'DASHBOARD',
    PRIMARY KEY (id)
);
COMMENT ON TABLE chart IS 'Custom chart table';
COMMENT ON COLUMN chart.id IS 'Primary key';
COMMENT ON COLUMN chart.gmt_create IS 'Creation time';
COMMENT ON COLUMN chart.gmt_modified IS 'Modified time';
COMMENT ON COLUMN chart.name IS 'Chart name';
COMMENT ON COLUMN chart.description IS 'Chart description';
COMMENT ON COLUMN chart.schema IS 'Chart info';
COMMENT ON COLUMN chart.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN chart.type IS 'Database type';
COMMENT ON COLUMN chart.database_name IS 'Database name';
COMMENT ON COLUMN chart.ddl IS 'DDL content';
COMMENT ON COLUMN chart.deleted IS 'Deleted status (y/n)';
COMMENT ON COLUMN chart.user_id IS 'User ID';
COMMENT ON COLUMN chart.schema_name IS 'Schema name';
COMMENT ON COLUMN chart.source_type IS 'Chart source type';
CREATE INDEX idx_chart_source_type ON chart(source_type);

-- Dashboard chart relation table
CREATE TABLE IF NOT EXISTS dashboard_chart_relation (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dashboard_id BIGINT NOT NULL DEFAULT 0,
    chart_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
COMMENT ON TABLE dashboard_chart_relation IS 'Dashboard chart relation table';
COMMENT ON COLUMN dashboard_chart_relation.id IS 'Primary key';
COMMENT ON COLUMN dashboard_chart_relation.gmt_create IS 'Creation time';
COMMENT ON COLUMN dashboard_chart_relation.gmt_modified IS 'Modified time';
COMMENT ON COLUMN dashboard_chart_relation.dashboard_id IS 'Dashboard ID';
COMMENT ON COLUMN dashboard_chart_relation.chart_id IS 'Chart ID';
