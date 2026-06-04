-- Reference documents for AI Integration (PDF, docx, text, etc.)
CREATE TABLE IF NOT EXISTS reference_document (
    id              BIGSERIAL    NOT NULL,
    gmt_create      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id         BIGINT       NOT NULL,
    filename        VARCHAR(512) NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    file_hash       VARCHAR(64)  NOT NULL,
    storage_type    VARCHAR(16)  NOT NULL DEFAULT 'db',
    content         BYTEA        NULL,
    storage_path    VARCHAR(1024) NULL,
    extracted_text  TEXT         NULL,
    chunk_count     INT          NOT NULL DEFAULT 0,
    index_status    VARCHAR(32)  NOT NULL DEFAULT 'pending',
    index_error     TEXT         NULL,
    deleted         VARCHAR(1)   NOT NULL DEFAULT 'n',
    PRIMARY KEY (id)
);

COMMENT ON TABLE reference_document IS 'AI Integration reference documents (uploaded PDF/docx/text)';
COMMENT ON COLUMN reference_document.storage_type IS 'db = BYTEA inline; file = ~/.inquery/documents/';
COMMENT ON COLUMN reference_document.index_status IS 'pending | indexed | error | skipped';

CREATE INDEX idx_reference_document_user ON reference_document(user_id);
CREATE INDEX idx_reference_document_hash ON reference_document(user_id, file_hash);
CREATE UNIQUE INDEX uq_reference_document_user_hash ON reference_document(user_id, file_hash)
    WHERE COALESCE(deleted, 'n') = 'n';
