-- AI Chat room table
CREATE TABLE IF NOT EXISTS ai_chat_room (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    conversation_id VARCHAR(64) NOT NULL,
    title VARCHAR(256) DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    deleted VARCHAR(10) DEFAULT 'n',
    PRIMARY KEY (id),
    UNIQUE (conversation_id)
);
COMMENT ON TABLE ai_chat_room IS 'AI chat room';
COMMENT ON COLUMN ai_chat_room.id IS 'Primary key';
COMMENT ON COLUMN ai_chat_room.gmt_create IS 'Creation time';
COMMENT ON COLUMN ai_chat_room.gmt_modified IS 'Modified time';
COMMENT ON COLUMN ai_chat_room.conversation_id IS 'UUID for backend context';
COMMENT ON COLUMN ai_chat_room.title IS 'Chat room title';
COMMENT ON COLUMN ai_chat_room.user_id IS 'User ID';
COMMENT ON COLUMN ai_chat_room.deleted IS 'Deleted status (y/n)';

CREATE INDEX idx_ai_chat_room_user_id ON ai_chat_room(user_id);
CREATE INDEX idx_ai_chat_room_gmt_modified ON ai_chat_room(gmt_modified);

-- AI Chat message table
CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    chat_room_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT DEFAULT NULL,
    user_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
COMMENT ON TABLE ai_chat_message IS 'AI chat message';
COMMENT ON COLUMN ai_chat_message.id IS 'Primary key';
COMMENT ON COLUMN ai_chat_message.gmt_create IS 'Creation time';
COMMENT ON COLUMN ai_chat_message.gmt_modified IS 'Modified time';
COMMENT ON COLUMN ai_chat_message.chat_room_id IS 'Chat room ID';
COMMENT ON COLUMN ai_chat_message.role IS 'Role (user/assistant)';
COMMENT ON COLUMN ai_chat_message.content IS 'Message content';
COMMENT ON COLUMN ai_chat_message.user_id IS 'User ID';

CREATE INDEX idx_ai_chat_message_room_id ON ai_chat_message(chat_room_id);
CREATE INDEX idx_ai_chat_message_user_id ON ai_chat_message(user_id);
CREATE INDEX idx_ai_chat_message_gmt_create ON ai_chat_message(gmt_create);
