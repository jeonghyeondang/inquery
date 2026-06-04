package ai.inquery.server.domain.api.enums;

import lombok.Getter;

@Getter
public enum VectorStoreType {

    PGVECTOR("pgvector", "pgvector", "PostgreSQL vector extension (uses existing database)"),
    QDRANT("qdrant", "Qdrant", "Open-source vector database (Docker/Cloud)"),
    PINECONE("pinecone", "Pinecone", "Cloud-hosted vector database");

    private final String code;
    private final String displayName;
    private final String description;

    VectorStoreType(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    public static VectorStoreType fromCode(String code) {
        if (code == null) return PGVECTOR;
        for (VectorStoreType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return PGVECTOR;
    }
}
