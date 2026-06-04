package ai.inquery.server.web.api.controller.ai.pinecone.bm25;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 sparse vector encoder for Pinecone hybrid search.
 * Converts text into sparse vectors using BM25 TF-IDF scoring with FNV-1a hashing.
 * Compatible with Pinecone's sparse vector format for hybrid (dense + sparse) queries.
 *
 * Usage:
 * 1. fit(corpus) — compute IDF from all schema documents during upsert
 * 2. transform(text) — generate sparse vector for a document (upsert-time)
 * 3. encodeQuery(text) — generate sparse vector for a query (search-time)
 */
@Slf4j
public class BM25SparseEncoder {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    // Tokenization: split on whitespace and punctuation, but preserve underscores (important for column names)
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9_]+");

    // SQL and common stopwords to filter out (using HashSet to allow merging duplicates)
    private static final Set<String> STOPWORDS = new HashSet<>(java.util.Arrays.asList(
        // English stopwords
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "need", "dare", "ought",
        "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
        "as", "into", "through", "during", "before", "after", "above", "below",
        "between", "out", "off", "over", "under", "again", "further", "then",
        "once", "here", "there", "when", "where", "why", "how", "all", "each",
        "every", "both", "few", "more", "most", "other", "some", "such", "no",
        "nor", "not", "only", "own", "same", "so", "than", "too", "very",
        "and", "but", "or", "if", "while", "that", "this", "it", "its",
        // SQL keywords
        "select", "from", "where", "insert", "update", "delete", "create",
        "drop", "alter", "table", "index", "view", "into", "values", "set",
        "null", "like", "exists",
        "join", "left", "right", "inner", "outer", "group", "by",
        "order", "having", "limit", "offset", "union", "distinct",
        "case", "when", "else", "end", "asc", "desc"
    ));

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Fitted parameters
    private Map<Long, Double> idfMap = new HashMap<>();
    private Map<Long, Integer> docFreqMap = new HashMap<>(); // document frequency per token hash (for incremental updates)
    private long totalTokenCount = 0;
    private double avgDocLength = 0.0;
    private int documentCount = 0;
    private boolean fitted = false;

    /**
     * Tokenize text: lowercase, split on non-alphanumeric (preserve underscores), remove stopwords.
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String lower = text.toLowerCase(Locale.ENGLISH);
        var matcher = TOKEN_PATTERN.matcher(lower);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * FNV-1a 32-bit hash, converted to unsigned long (0 to 2^32-1).
     * Same approach as Python pinecone-text library for compatibility.
     */
    public static long fnv1aHash(String token) {
        final long FNV_OFFSET = 2166136261L;
        final long FNV_PRIME = 16777619L;
        long hash = FNV_OFFSET;
        for (int i = 0; i < token.length(); i++) {
            hash ^= (token.charAt(i) & 0xFF);
            hash = (hash * FNV_PRIME) & 0xFFFFFFFFL; // keep 32-bit unsigned
        }
        return hash;
    }

    /**
     * Fit the encoder on a corpus of documents. Computes IDF for each token.
     *
     * @param documents List of document texts (e.g., all schema descriptions)
     */
    public void fit(List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("BM25 fit called with empty corpus");
            return;
        }

        this.documentCount = documents.size();

        // Count document frequency for each token hash
        this.docFreqMap = new HashMap<>();
        this.totalTokenCount = 0;

        for (String doc : documents) {
            List<String> tokens = tokenize(doc);
            totalTokenCount += tokens.size();

            // Unique token hashes in this document
            Set<Long> uniqueHashes = tokens.stream()
                .map(BM25SparseEncoder::fnv1aHash)
                .collect(Collectors.toSet());

            for (Long hash : uniqueHashes) {
                docFreqMap.merge(hash, 1, Integer::sum);
            }
        }

        this.avgDocLength = documentCount > 0 ? (double) totalTokenCount / documentCount : 1.0;

        // Compute IDF: log((N - df + 0.5) / (df + 0.5) + 1)
        this.idfMap = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : docFreqMap.entrySet()) {
            int df = entry.getValue();
            double idf = Math.log((documentCount - df + 0.5) / (df + 0.5) + 1.0);
            this.idfMap.put(entry.getKey(), idf);
        }

        this.fitted = true;
        log.info("BM25 encoder fitted: {} documents, {} unique tokens, avgDocLength={}",
            documentCount, idfMap.size(), String.format("%.1f", avgDocLength));
    }

    /**
     * Generate a sparse vector for a document (upsert-time).
     * Uses BM25 term frequency scoring.
     *
     * @param text Document text
     * @return SparseVector with indices and values
     */
    public SparseVector transform(String text) {
        if (!fitted) {
            throw new IllegalStateException("BM25 encoder not fitted. Call fit() first.");
        }
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return new SparseVector(Collections.emptyList(), Collections.emptyList());
        }

        // Count term frequency
        Map<Long, Integer> tf = new HashMap<>();
        for (String token : tokens) {
            long hash = fnv1aHash(token);
            tf.merge(hash, 1, Integer::sum);
        }

        int docLength = tokens.size();
        List<Long> indices = new ArrayList<>();
        List<Float> values = new ArrayList<>();

        for (Map.Entry<Long, Integer> entry : tf.entrySet()) {
            long hash = entry.getKey();
            int freq = entry.getValue();

            double idf = idfMap.getOrDefault(hash, 0.0);
            if (idf <= 0) continue;

            // BM25 score: IDF * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * docLen / avgDocLen))
            double numerator = freq * (K1 + 1);
            double denominator = freq + K1 * (1 - B + B * docLength / avgDocLength);
            double score = idf * (numerator / denominator);

            indices.add(hash);
            values.add((float) score);
        }

        return new SparseVector(indices, values);
    }

    /**
     * Generate a sparse vector for a query (search-time).
     * Uses simplified BM25 scoring (TF=1 for each query term) with IDF weighting.
     *
     * @param queryText Query text
     * @return SparseVector with indices and values
     */
    public SparseVector encodeQuery(String queryText) {
        if (!fitted) {
            throw new IllegalStateException("BM25 encoder not fitted. Call fit() first.");
        }
        List<String> tokens = tokenize(queryText);
        if (tokens.isEmpty()) {
            return new SparseVector(Collections.emptyList(), Collections.emptyList());
        }

        // For query encoding, use unique tokens with IDF as the score
        Map<Long, Double> queryScores = new LinkedHashMap<>();
        for (String token : tokens) {
            long hash = fnv1aHash(token);
            double idf = idfMap.getOrDefault(hash, 0.0);
            // Accumulate if same token appears multiple times
            queryScores.merge(hash, Math.max(idf, 0.0), Double::sum);
        }

        List<Long> indices = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : queryScores.entrySet()) {
            if (entry.getValue() > 0) {
                indices.add(entry.getKey());
                values.add(entry.getValue().floatValue());
            }
        }

        return new SparseVector(indices, values);
    }

    public boolean isFitted() {
        return fitted;
    }

    /**
     * Incrementally add a single document to the fitted BM25 model.
     * Updates documentCount, avgDocLength, docFreq, and IDF for affected tokens.
     * Much faster than full refit — O(tokens in document) instead of O(entire corpus).
     *
     * @param text Document text to add (e.g., a single table schema)
     */
    public void addDocument(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return;
        }

        // Update corpus stats
        documentCount++;
        totalTokenCount += tokens.size();
        avgDocLength = (double) totalTokenCount / documentCount;

        // Update document frequency for unique tokens in this document
        Set<Long> uniqueHashes = tokens.stream()
            .map(BM25SparseEncoder::fnv1aHash)
            .collect(Collectors.toSet());

        for (Long hash : uniqueHashes) {
            docFreqMap.merge(hash, 1, Integer::sum);
            // Recompute IDF only for affected tokens
            int df = docFreqMap.get(hash);
            double idf = Math.log((documentCount - df + 0.5) / (df + 0.5) + 1.0);
            idfMap.put(hash, idf);
        }

        fitted = true;
        log.debug("BM25 incremental update: documentCount={}, uniqueTokens={}, avgDocLength={}",
            documentCount, idfMap.size(), String.format("%.1f", avgDocLength));
    }

    /**
     * Serialize fitted parameters to JSON string for storage in config DB.
     */
    public String serialize() {
        if (!fitted) {
            throw new IllegalStateException("BM25 encoder not fitted. Call fit() first.");
        }
        try {
            BM25Params params = new BM25Params();
            params.idfMap = this.idfMap;
            params.docFreqMap = this.docFreqMap;
            params.avgDocLength = this.avgDocLength;
            params.documentCount = this.documentCount;
            params.totalTokenCount = this.totalTokenCount;
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize BM25 parameters", e);
        }
    }

    /**
     * Deserialize fitted parameters from JSON string (loaded from config DB).
     */
    public void deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("BM25 params JSON is empty");
        }
        try {
            BM25Params params = objectMapper.readValue(json, BM25Params.class);
            this.idfMap = params.idfMap != null ? params.idfMap : new HashMap<>();
            this.docFreqMap = params.docFreqMap != null ? params.docFreqMap : new HashMap<>();
            this.avgDocLength = params.avgDocLength;
            this.documentCount = params.documentCount;
            this.totalTokenCount = params.totalTokenCount;
            // Backward compatibility: estimate totalTokenCount if not stored
            if (this.totalTokenCount == 0 && this.documentCount > 0) {
                this.totalTokenCount = (long) (this.avgDocLength * this.documentCount);
            }
            this.fitted = !this.idfMap.isEmpty();
            log.info("BM25 encoder deserialized: {} documents, {} unique tokens",
                documentCount, idfMap.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize BM25 parameters", e);
        }
    }

    /**
     * Sparse vector result containing Pinecone-compatible indices and values.
     */
    public static class SparseVector {
        private final List<Long> indices;
        private final List<Float> values;

        public SparseVector(List<Long> indices, List<Float> values) {
            this.indices = indices;
            this.values = values;
        }

        public List<Long> getIndices() { return indices; }
        public List<Float> getValues() { return values; }

        public boolean isEmpty() {
            return indices == null || indices.isEmpty();
        }
    }

    /**
     * Serializable parameters for JSON storage.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BM25Params {
        public Map<Long, Double> idfMap;
        public Map<Long, Integer> docFreqMap;
        public double avgDocLength;
        public int documentCount;
        public long totalTokenCount;
    }
}
