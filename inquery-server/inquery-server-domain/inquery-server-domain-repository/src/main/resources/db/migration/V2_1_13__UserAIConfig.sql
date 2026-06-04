-- User AI configuration table
CREATE TABLE IF NOT EXISTS user_ai_config (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT NOT NULL,
    confluence_base_url VARCHAR(512) DEFAULT NULL,
    confluence_username VARCHAR(256) DEFAULT NULL,
    confluence_api_token VARCHAR(512) DEFAULT NULL,
    jira_base_url VARCHAR(512) DEFAULT NULL,
    jira_username VARCHAR(256) DEFAULT NULL,
    jira_api_token VARCHAR(512) DEFAULT NULL,
    slack_user_token VARCHAR(512) DEFAULT NULL,
    github_token VARCHAR(512) DEFAULT NULL,
    github_base_url VARCHAR(512) DEFAULT NULL,
    github_organization VARCHAR(256) DEFAULT NULL,
    outlook_access_token TEXT DEFAULT NULL,
    outlook_user_email VARCHAR(256) DEFAULT NULL,
    outlook_refresh_token TEXT DEFAULT NULL,
    outlook_expires_at BIGINT DEFAULT NULL,
    outlook_tenant_id VARCHAR(128) DEFAULT NULL,
    outlook_client_id VARCHAR(128) DEFAULT NULL,
    outlook_client_secret VARCHAR(512) DEFAULT NULL,
    gemini_model VARCHAR(128) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id)
);
COMMENT ON TABLE user_ai_config IS 'User AI integration configuration';
COMMENT ON COLUMN user_ai_config.id IS 'Primary key';
COMMENT ON COLUMN user_ai_config.gmt_create IS 'Creation time';
COMMENT ON COLUMN user_ai_config.gmt_modified IS 'Modified time';
COMMENT ON COLUMN user_ai_config.user_id IS 'User ID';
COMMENT ON COLUMN user_ai_config.confluence_base_url IS 'Confluence base URL';
COMMENT ON COLUMN user_ai_config.confluence_username IS 'Confluence username';
COMMENT ON COLUMN user_ai_config.confluence_api_token IS 'Confluence API token';
COMMENT ON COLUMN user_ai_config.jira_base_url IS 'JIRA base URL';
COMMENT ON COLUMN user_ai_config.jira_username IS 'JIRA username';
COMMENT ON COLUMN user_ai_config.jira_api_token IS 'JIRA API token';
COMMENT ON COLUMN user_ai_config.slack_user_token IS 'Slack user token';
COMMENT ON COLUMN user_ai_config.github_token IS 'GitHub token';
COMMENT ON COLUMN user_ai_config.github_base_url IS 'GitHub base URL';
COMMENT ON COLUMN user_ai_config.github_organization IS 'GitHub organization';
COMMENT ON COLUMN user_ai_config.outlook_access_token IS 'Outlook access token';
COMMENT ON COLUMN user_ai_config.outlook_user_email IS 'Outlook user email';
COMMENT ON COLUMN user_ai_config.outlook_refresh_token IS 'Outlook refresh token';
COMMENT ON COLUMN user_ai_config.outlook_expires_at IS 'Outlook access token expires at (epoch millis)';
COMMENT ON COLUMN user_ai_config.outlook_tenant_id IS 'Outlook Azure tenant ID';
COMMENT ON COLUMN user_ai_config.outlook_client_id IS 'Outlook Azure client ID';
COMMENT ON COLUMN user_ai_config.outlook_client_secret IS 'Outlook Azure client secret (optional)';
COMMENT ON COLUMN user_ai_config.gemini_model IS 'Gemini model name';
