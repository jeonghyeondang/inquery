-- Presenton AI Presentation Configuration Table
-- Stores LLM and image generation settings for Presenton integration

CREATE TABLE IF NOT EXISTS presenton_config (
    id BIGSERIAL PRIMARY KEY,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT DEFAULT 0,
    config_json TEXT NOT NULL,
    UNIQUE (user_id)
);

COMMENT ON TABLE presenton_config IS 'Presenton AI Presentation Configuration';
COMMENT ON COLUMN presenton_config.id IS 'Primary key';
COMMENT ON COLUMN presenton_config.gmt_create IS 'Creation time';
COMMENT ON COLUMN presenton_config.gmt_modified IS 'Last modified time';
COMMENT ON COLUMN presenton_config.user_id IS 'User ID (0 for global config)';
COMMENT ON COLUMN presenton_config.config_json IS 'LLM and image generation configuration as JSON';
