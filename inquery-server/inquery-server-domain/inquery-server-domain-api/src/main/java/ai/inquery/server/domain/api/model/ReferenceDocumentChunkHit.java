package ai.inquery.server.domain.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A semantic-search hit from an uploaded reference document chunk.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceDocumentChunkHit {

    private Long documentId;
    private String filename;
    private int chunkIndex;
    private String text;
    private float score;
}
