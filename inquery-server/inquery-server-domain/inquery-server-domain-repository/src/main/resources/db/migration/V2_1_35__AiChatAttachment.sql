-- AI Chat attachment table
-- Stores files uploaded into ai-chat conversations (images, PDFs,
-- text-family files). Binary content lives in `content` (BYTEA) so the
-- product DB is self-contained for desktop builds. `extracted_text`
-- holds the LLM-prompt-ready text for PDFs/text files (also used for
-- in-chat search and fallback when the active model lacks PDF support).
-- `thumbnail_content` holds a 320px PNG preview (PDF first page or
-- resized image) generated synchronously at upload time.
CREATE TABLE IF NOT EXISTS ai_chat_attachment (
    id                  BIGSERIAL    NOT NULL,
    gmt_create          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id             BIGINT       NOT NULL,
    chat_room_id        BIGINT       NULL,
    filename            VARCHAR(512) NOT NULL,
    mime_type           VARCHAR(128) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    kind                VARCHAR(16)  NOT NULL,
    content             BYTEA        NOT NULL,
    extracted_text      TEXT         NULL,
    thumbnail_content   BYTEA        NULL,
    thumbnail_mime      VARCHAR(64)  NULL,
    deleted             VARCHAR(10)  DEFAULT 'n',
    PRIMARY KEY (id)
);
COMMENT ON TABLE  ai_chat_attachment IS 'AI chat attachment (image / pdf / text-family)';
COMMENT ON COLUMN ai_chat_attachment.kind IS 'image | pdf | text';
COMMENT ON COLUMN ai_chat_attachment.content IS 'Original binary payload';
COMMENT ON COLUMN ai_chat_attachment.extracted_text IS 'Extracted text for PDF/text-family; used for search and LLM fallback';
COMMENT ON COLUMN ai_chat_attachment.thumbnail_content IS '320px preview PNG (PDF first page or resized image)';

CREATE INDEX idx_ai_chat_attachment_user ON ai_chat_attachment(user_id);
CREATE INDEX idx_ai_chat_attachment_room ON ai_chat_attachment(chat_room_id);

-- N:N mapping so the same attachment can be re-used across multiple
-- messages without duplicating the BYTEA blob. `position` preserves the
-- order the user pinned the attachments in a single message.
CREATE TABLE IF NOT EXISTS ai_chat_message_attachment (
    message_id     BIGINT NOT NULL,
    attachment_id  BIGINT NOT NULL,
    position       INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, attachment_id)
);
CREATE INDEX idx_ai_chat_msg_att_message    ON ai_chat_message_attachment(message_id);
CREATE INDEX idx_ai_chat_msg_att_attachment ON ai_chat_message_attachment(attachment_id);
