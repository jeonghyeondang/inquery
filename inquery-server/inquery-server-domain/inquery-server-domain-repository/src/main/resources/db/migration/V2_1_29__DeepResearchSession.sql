-- Deep Research Session table for storing temporary research data
-- This table stores the intermediate MD content during research execution
-- and the final report JSON after completion

CREATE TABLE IF NOT EXISTS deep_research_session (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    chat_room_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    research_plan TEXT,
    md_content TEXT,
    report_json TEXT,
    infographic_html TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNING',
    current_iteration INTEGER DEFAULT 0,
    total_queries_executed INTEGER DEFAULT 0,
    error_message TEXT,
    user_id BIGINT,
    PRIMARY KEY (id)
);

COMMENT ON TABLE deep_research_session IS 'Deep Research Session';
COMMENT ON COLUMN deep_research_session.id IS 'Primary key';
COMMENT ON COLUMN deep_research_session.gmt_create IS 'Creation time';
COMMENT ON COLUMN deep_research_session.gmt_modified IS 'Modified time';
COMMENT ON COLUMN deep_research_session.chat_room_id IS 'Chat room ID';
COMMENT ON COLUMN deep_research_session.question IS 'Research question';
COMMENT ON COLUMN deep_research_session.research_plan IS 'Research plan';
COMMENT ON COLUMN deep_research_session.md_content IS 'Markdown content';
COMMENT ON COLUMN deep_research_session.report_json IS 'Report JSON';
COMMENT ON COLUMN deep_research_session.status IS 'Status';
COMMENT ON COLUMN deep_research_session.current_iteration IS 'Current iteration';
COMMENT ON COLUMN deep_research_session.total_queries_executed IS 'Total queries executed';
COMMENT ON COLUMN deep_research_session.error_message IS 'Error message';
COMMENT ON COLUMN deep_research_session.infographic_html IS 'Infographic HTML content';
COMMENT ON COLUMN deep_research_session.user_id IS 'User ID';

CREATE INDEX idx_drs_chat_room_id ON deep_research_session(chat_room_id);
CREATE INDEX idx_drs_status ON deep_research_session(status);
CREATE INDEX idx_drs_user_id ON deep_research_session(user_id);
