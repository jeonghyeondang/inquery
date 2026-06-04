package ai.inquery.server.domain.api.service;

import java.util.List;

/**
 * Service for vector search operations.
 */
public interface VectorSearchService {

    /**
     * Generates a vector embedding for the given text.
     *
     * @param text The text to embed.
     * @return A list of floats representing the vector embedding.
     */
    List<Double> generateEmbedding(String text);

    /**
     * Searches for relevant documents using the given query vector.
     *
     * @param queryVector The query vector.
     * @param limit       The maximum number of results to return.
     * @return A list of relevant documents (e.g., schema information).
     */
    /**
     * Searches for relevant documents using the given query vector.
     *
     * @param queryVector The query vector.
     * @param limit       The maximum number of results to return.
     * @return A list of relevant documents (e.g., schema information).
     */
    List<String> search(List<Double> queryVector, int limit);

    /**
     * Searches for relevant documents using the given query vector and original query text.
     * Implementations may use the queryText for additional ranking/reranking.
     *
     * @param queryVector The query vector.
     * @param queryText   The original query text (for reranking).
     * @param limit       The maximum number of results to return.
     * @param excludedTables List of table names or "schema.table" to exclude from search.
     * @return A list of relevant documents (e.g., schema information).
     */
    default List<String> search(List<Double> queryVector, String queryText, int limit, List<String> excludedTables) {
        return search(queryVector, limit, excludedTables);
    }

    /**
     * Searches for relevant documents using the given query vector, excluding specific tables.
     *
     * @param queryVector   The query vector.
     * @param limit         The maximum number of results to return.
     * @param excludedTables List of table names or "schema.table" to exclude from search.
     * @return A list of relevant documents (e.g., schema information).
     */
    default List<String> search(List<Double> queryVector, int limit, List<String> excludedTables) {
        return search(queryVector, limit); // Default to search without exclusion if not implemented
    }

    /**
     * Searches for schema by exact table names using metadata filter.
     * More efficient than semantic search when table names are already known.
     *
     * @param tableNames List of table names to search for.
     * @return Schema information for matching tables.
     */
    default List<String> searchByTableNames(List<String> tableNames) {
        return new java.util.ArrayList<>();
    }

    /**
     * Ensures the vector DB connection is ready.
     * Call this early to warm up the connection while other operations are in progress.
     * This is a non-blocking hint - implementations may ignore it if already connected.
     */
    default void ensureConnection() {
        // Default implementation does nothing - override if connection warmup is needed
    }
}
