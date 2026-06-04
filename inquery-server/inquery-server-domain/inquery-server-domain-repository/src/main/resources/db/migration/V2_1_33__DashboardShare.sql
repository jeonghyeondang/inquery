-- Add public sharing support for dashboards
ALTER TABLE dashboard ADD COLUMN IF NOT EXISTS share_token VARCHAR(64) DEFAULT NULL;
ALTER TABLE dashboard ADD COLUMN IF NOT EXISTS is_public VARCHAR(1) DEFAULT 'n';

CREATE UNIQUE INDEX IF NOT EXISTS idx_dashboard_share_token ON dashboard (share_token) WHERE share_token IS NOT NULL;

COMMENT ON COLUMN dashboard.share_token IS 'Unique token for public sharing (UUID)';
COMMENT ON COLUMN dashboard.is_public IS 'Whether dashboard is publicly accessible (y/n)';
