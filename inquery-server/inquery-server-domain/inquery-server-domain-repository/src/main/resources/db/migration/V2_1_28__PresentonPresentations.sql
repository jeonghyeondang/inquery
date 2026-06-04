-- Presenton Presentations Table
-- Stores generated presentations locally

CREATE TABLE IF NOT EXISTS presenton_presentation (
    id VARCHAR(64) PRIMARY KEY,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT DEFAULT 0,
    title VARCHAR(500),
    content TEXT,
    n_slides INTEGER DEFAULT 0,
    language VARCHAR(50),
    tone VARCHAR(50),
    verbosity VARCHAR(50),
    template VARCHAR(100),
    outlines_json TEXT,
    structure_json TEXT,
    status VARCHAR(50) DEFAULT 'draft'
);

CREATE INDEX idx_presenton_presentation_user ON presenton_presentation(user_id);
CREATE INDEX idx_presenton_presentation_status ON presenton_presentation(status);

COMMENT ON TABLE presenton_presentation IS 'Presenton AI Presentations';
COMMENT ON COLUMN presenton_presentation.id IS 'Presentation UUID';
COMMENT ON COLUMN presenton_presentation.title IS 'Presentation title';
COMMENT ON COLUMN presenton_presentation.content IS 'Original prompt/content';
COMMENT ON COLUMN presenton_presentation.n_slides IS 'Number of slides';
COMMENT ON COLUMN presenton_presentation.outlines_json IS 'Slide outlines as JSON';
COMMENT ON COLUMN presenton_presentation.structure_json IS 'Presentation structure as JSON';
COMMENT ON COLUMN presenton_presentation.status IS 'Status: draft, generating, completed';

-- Presenton Slides Table
CREATE TABLE IF NOT EXISTS presenton_slide (
    id VARCHAR(64) PRIMARY KEY,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    presentation_id VARCHAR(64) NOT NULL,
    slide_index INTEGER NOT NULL,
    layout_group VARCHAR(100),
    layout VARCHAR(100),
    content_json TEXT,
    speaker_note TEXT,
    CONSTRAINT fk_slide_presentation FOREIGN KEY (presentation_id) REFERENCES presenton_presentation(id) ON DELETE CASCADE
);

CREATE INDEX idx_presenton_slide_presentation ON presenton_slide(presentation_id);
CREATE INDEX idx_presenton_slide_index ON presenton_slide(presentation_id, slide_index);

COMMENT ON TABLE presenton_slide IS 'Presenton Presentation Slides';
COMMENT ON COLUMN presenton_slide.presentation_id IS 'Parent presentation ID';
COMMENT ON COLUMN presenton_slide.slide_index IS 'Slide order index';
COMMENT ON COLUMN presenton_slide.content_json IS 'Slide content as JSON';
COMMENT ON COLUMN presenton_slide.speaker_note IS 'Speaker notes';
