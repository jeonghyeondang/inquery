package ai.inquery.server.tools.base.wrapper;

/**
 * Is it possible to track
 *
 */
public interface Traceable {
    /**
     * Get traceId
     *
     * @return traceId
     */
    String getTraceId();

    /**
     * Set traceId
     *
     * @param traceId
     */
    void setTraceId(String traceId);
}
