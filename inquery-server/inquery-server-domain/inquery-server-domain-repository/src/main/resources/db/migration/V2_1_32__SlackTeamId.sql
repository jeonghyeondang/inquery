-- Add slack_team_id column (auto-resolved from Slack API)
ALTER TABLE user_ai_config ADD COLUMN IF NOT EXISTS slack_team_id VARCHAR(64) DEFAULT NULL;
COMMENT ON COLUMN user_ai_config.slack_team_id IS 'Slack team ID (auto-resolved via auth.test API)';
