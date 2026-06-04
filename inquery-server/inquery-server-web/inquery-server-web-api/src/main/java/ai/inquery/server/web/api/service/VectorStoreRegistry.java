package ai.inquery.server.web.api.service;

import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry / factory for vector store providers.
 * Reads the active provider type from system_config ("vectordb.type")
 * and returns the corresponding implementation.
 * Default is PGVECTOR (no external dependencies, uses the existing PostgreSQL).
 */
@Slf4j
@Service
public class VectorStoreRegistry {

    public static final String CONFIG_VECTORDB_TYPE = "vectordb.type";

    private final ConfigService configService;
    private final Map<VectorStoreType, VectorStoreProvider> providers = new HashMap<>();

    public VectorStoreRegistry(ConfigService configService,
                               List<VectorStoreProvider> providerList) {
        this.configService = configService;
        for (VectorStoreProvider provider : providerList) {
            providers.put(provider.getType(), provider);
            log.info("Registered VectorStoreProvider: {}", provider.getType());
        }
    }

    /**
     * @return the currently active vector store provider based on config
     */
    public VectorStoreProvider getActiveProvider() {
        VectorStoreType type = getActiveType();
        VectorStoreProvider provider = providers.get(type);
        if (provider == null) {
            log.warn("No VectorStoreProvider found for type {}, falling back to PGVECTOR", type);
            provider = providers.get(VectorStoreType.PGVECTOR);
        }
        return provider;
    }

    /**
     * @return the provider for a specific type (regardless of which is active)
     */
    public VectorStoreProvider getProvider(VectorStoreType type) {
        return providers.get(type);
    }

    /**
     * @return the currently configured vector store type
     */
    public VectorStoreType getActiveType() {
        try {
            Config cfg = configService.find(CONFIG_VECTORDB_TYPE).getData();
            if (cfg != null && StringUtils.isNotBlank(cfg.getContent())) {
                return VectorStoreType.fromCode(cfg.getContent());
            }
        } catch (Exception e) {
            log.debug("Failed to read vectordb.type config, using default: {}", e.getMessage());
        }
        return VectorStoreType.PGVECTOR;
    }

    /**
     * @return all registered provider types
     */
    public Map<VectorStoreType, VectorStoreProvider> getAllProviders() {
        return providers;
    }
}
