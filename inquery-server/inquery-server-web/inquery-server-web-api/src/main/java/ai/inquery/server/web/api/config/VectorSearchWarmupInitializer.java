package ai.inquery.server.web.api.config;

import ai.inquery.server.domain.core.query.SchemaSearcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pre-warms the local embedding model used by schema search.
 *
 * <p>The first workspace SQL generation otherwise pays the ONNX model load
 * cost (usually 1-5s) on the user request path. Run this asynchronously after
 * the app is ready so startup is not blocked and the first real schema search
 * can go straight to vector lookup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSearchWarmupInitializer {

    private final SchemaSearcher schemaSearcher;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpVectorSearch() {
        Thread t = new Thread(() -> {
            try {
                // Give Flyway, sample DB init, and startup HTTP probes a brief
                // head start; this is purely latency optimization.
                Thread.sleep(2_000L);
                long start = System.currentTimeMillis();
                schemaSearcher.warmUp();
                log.info("Vector search warmup completed in {}ms", System.currentTimeMillis() - start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Vector search warmup failed (non-fatal): {}", e.getMessage());
            }
        }, "vector-search-warmup");
        t.setDaemon(true);
        t.start();
    }
}
