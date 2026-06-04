-- AI Feedback table for learning from user feedback
CREATE TABLE IF NOT EXISTS ai_feedback (
    id BIGSERIAL PRIMARY KEY,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Feedback type
    feedback_type VARCHAR(20) NOT NULL,
    response_type VARCHAR(30) NOT NULL,
    
    -- AI Chat related
    chat_room_id BIGINT,
    message_id BIGINT,
    question TEXT,
    generated_content TEXT,
    
    -- Deep Research related
    research_session_id BIGINT,
    
    -- Context
    data_source_id BIGINT,
    database_name VARCHAR(128),
    user_id BIGINT NOT NULL
);

COMMENT ON TABLE ai_feedback IS 'AI Feedback for learning';
COMMENT ON COLUMN ai_feedback.id IS 'Primary key';
COMMENT ON COLUMN ai_feedback.gmt_create IS 'Creation time';
COMMENT ON COLUMN ai_feedback.gmt_modified IS 'Modified time';
COMMENT ON COLUMN ai_feedback.feedback_type IS 'POSITIVE or NEGATIVE';
COMMENT ON COLUMN ai_feedback.response_type IS 'SQL_GENERATION, RESULT_INTERPRETATION, DEEP_RESEARCH';
COMMENT ON COLUMN ai_feedback.chat_room_id IS 'Chat room ID';
COMMENT ON COLUMN ai_feedback.message_id IS 'Message ID in chat';
COMMENT ON COLUMN ai_feedback.question IS 'Original user question';
COMMENT ON COLUMN ai_feedback.generated_content IS 'Generated SQL or interpretation text';
COMMENT ON COLUMN ai_feedback.research_session_id IS 'Deep Research session ID';
COMMENT ON COLUMN ai_feedback.data_source_id IS 'Data source ID';
COMMENT ON COLUMN ai_feedback.database_name IS 'Database name';
COMMENT ON COLUMN ai_feedback.user_id IS 'User who gave feedback';

CREATE INDEX idx_feedback_type ON ai_feedback(feedback_type);
CREATE INDEX idx_response_type ON ai_feedback(response_type);
CREATE INDEX idx_ai_feedback_data_source ON ai_feedback(data_source_id);
CREATE INDEX idx_ai_feedback_user ON ai_feedback(user_id);
CREATE INDEX idx_ai_feedback_chat_room ON ai_feedback(chat_room_id);
