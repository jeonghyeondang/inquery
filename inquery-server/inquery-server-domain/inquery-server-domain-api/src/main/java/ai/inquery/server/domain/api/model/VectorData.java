package ai.inquery.server.domain.api.model;

import java.util.List;
import java.util.Map;

/**
 * Shared vector data DTO used across all vector store providers.
 */
public class VectorData {

    private String id;
    private List<Float> values;
    private Map<String, String> metadata;
    private List<Long> sparseIndices;
    private List<Float> sparseValues;

    public VectorData() {}

    public VectorData(String id, List<Float> values, Map<String, String> metadata) {
        this.id = id;
        this.values = values;
        this.metadata = metadata;
    }

    public VectorData(String id, List<Float> values, Map<String, String> metadata,
                      List<Long> sparseIndices, List<Float> sparseValues) {
        this.id = id;
        this.values = values;
        this.metadata = metadata;
        this.sparseIndices = sparseIndices;
        this.sparseValues = sparseValues;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public List<Float> getValues() { return values; }
    public void setValues(List<Float> values) { this.values = values; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public List<Long> getSparseIndices() { return sparseIndices; }
    public void setSparseIndices(List<Long> sparseIndices) { this.sparseIndices = sparseIndices; }
    public List<Float> getSparseValues() { return sparseValues; }
    public void setSparseValues(List<Float> sparseValues) { this.sparseValues = sparseValues; }
    public boolean hasSparseVector() { return sparseIndices != null && !sparseIndices.isEmpty(); }
}
