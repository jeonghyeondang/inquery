package ai.inquery.server.domain.api.model;

import java.util.Map;

/**
 * Shared search result DTO used across all vector store providers.
 */
public class VectorSearchResult {

    private String id;
    private float score;
    private Map<String, String> metadata;

    public VectorSearchResult() {}

    public VectorSearchResult(String id, float score, Map<String, String> metadata) {
        this.id = id;
        this.score = score;
        this.metadata = metadata;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public float getScore() { return score; }
    public void setScore(float score) { this.score = score; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
