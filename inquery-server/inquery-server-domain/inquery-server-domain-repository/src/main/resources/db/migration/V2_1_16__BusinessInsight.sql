-- Database business insight table
CREATE TABLE IF NOT EXISTS database_business_insight (
    id BIGSERIAL NOT NULL,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(256) DEFAULT '',
    play_store_link VARCHAR(1024) DEFAULT NULL,
    app_store_link VARCHAR(1024) DEFAULT NULL,
    web_link VARCHAR(1024) DEFAULT NULL,
    insight_content TEXT DEFAULT NULL,
    reference_links TEXT DEFAULT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (data_source_id, database_name)
);
COMMENT ON TABLE database_business_insight IS 'Database Business Insight';
COMMENT ON COLUMN database_business_insight.id IS 'Primary key';
COMMENT ON COLUMN database_business_insight.data_source_id IS 'Data source connection ID';
COMMENT ON COLUMN database_business_insight.database_name IS 'Database name';
COMMENT ON COLUMN database_business_insight.play_store_link IS 'Google Play Store Link';
COMMENT ON COLUMN database_business_insight.app_store_link IS 'Apple App Store Link';
COMMENT ON COLUMN database_business_insight.web_link IS 'Web Site Link';
COMMENT ON COLUMN database_business_insight.insight_content IS 'Business Insight Content (Markdown)';
COMMENT ON COLUMN database_business_insight.reference_links IS 'Reference Links (JSON string)';
COMMENT ON COLUMN database_business_insight.create_time IS 'Creation time';
COMMENT ON COLUMN database_business_insight.update_time IS 'Modified time';

CREATE INDEX idx_business_insight_data_source_id ON database_business_insight(data_source_id);
