package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.ReferenceDocumentChunkHit;

import java.util.Collections;
import java.util.List;

/**
 * Semantic search over user-uploaded reference documents ({@code ref_docs} namespace).
 */
public interface ReferenceDocumentSearchService {

    String NAMESPACE = "ref_docs";

    /**
     * Search indexed reference-document chunks for the given user.
     */
    List<ReferenceDocumentChunkHit> search(Long userId, String query, int topK);

    /**
     * @return true when the user has at least one indexed reference document.
     */
    boolean hasIndexedDocuments(Long userId);

    /**
     * Format hits as Markdown for LLM prompts.
     */
    default String formatAsMarkdown(List<ReferenceDocumentChunkHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ReferenceDocumentChunkHit hit : hits) {
            if (sb.length() > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append("Document: ").append(hit.getFilename() != null ? hit.getFilename() : "unknown");
            sb.append(" (chunk ").append(hit.getChunkIndex()).append(")\n");
            if (hit.getText() != null) {
                sb.append(hit.getText());
            }
        }
        return sb.toString();
    }

    default List<ReferenceDocumentChunkHit> emptyHits() {
        return Collections.emptyList();
    }
}
