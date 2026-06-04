package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.core.util.TableNameExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Abstract search service class
 * 
 * Provides common functionality:
 * - Table name extraction
 * - WebClient and ObjectMapper management
 * - Common error handling
 */
public abstract class AbstractSearchService {
    
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;
    
    protected AbstractSearchService(WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Extract actual table name from full table name
     * 
     * @param fullTableName Full table name
     * @return Actual table name
     */
    protected String extractTableName(String fullTableName) {
        return TableNameExtractor.extract(fullTableName);
    }
    
    /**
     * Common error handling: Convert 4xx/5xx errors to RuntimeException
     */
    protected Mono<? extends Throwable> handleErrorResponse(org.springframework.web.reactive.function.client.ClientResponse clientResponse) {
        int statusCode = clientResponse.statusCode().value();
        return clientResponse.bodyToMono(String.class)
            .flatMap(errorBody -> {
                logger.error("API call failed (HTTP {}): {}", statusCode, errorBody);
                return Mono.<RuntimeException>error(new RuntimeException("API call failed: " + statusCode));
            })
            .cast(RuntimeException.class)
            .onErrorResume(e -> Mono.<RuntimeException>error(new RuntimeException("API call failed: " + statusCode)));
    }
    
    /**
     * Common timeout and retry settings
     */
    protected Mono<String> withTimeoutAndRetry(Mono<String> mono) {
        return mono
            .timeout(Duration.ofSeconds(30))
            .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)));
    }
}

