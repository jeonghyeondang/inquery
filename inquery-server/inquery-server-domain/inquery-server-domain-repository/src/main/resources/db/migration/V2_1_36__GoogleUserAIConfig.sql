-- Google Drive/Docs/Sheets integration (BYO OAuth credentials) for user-scoped AI settings.
ALTER TABLE user_ai_config ADD COLUMN IF NOT EXISTS google_client_id VARCHAR(512) DEFAULT NULL;
ALTER TABLE user_ai_config ADD COLUMN IF NOT EXISTS google_client_secret VARCHAR(512) DEFAULT NULL;
ALTER TABLE user_ai_config ADD COLUMN IF NOT EXISTS google_access_token VARCHAR(4096) DEFAULT NULL;
ALTER TABLE user_ai_config ADD COLUMN IF NOT EXISTS google_refresh_token VARCHAR(4096) DEFAULT NULL;
ALTER TABLE user_ai_config ADD COLUMN IF NOT EXISTS google_expires_at BIGINT DEFAULT NULL;

COMMENT ON COLUMN user_ai_config.google_client_id IS 'Google OAuth client ID (BYO credentials)';
COMMENT ON COLUMN user_ai_config.google_client_secret IS 'Google OAuth client secret (BYO credentials)';
COMMENT ON COLUMN user_ai_config.google_access_token IS 'Google access token';
COMMENT ON COLUMN user_ai_config.google_refresh_token IS 'Google refresh token (enables auto refresh)';
COMMENT ON COLUMN user_ai_config.google_expires_at IS 'Google access token expires at (epoch millis)';
