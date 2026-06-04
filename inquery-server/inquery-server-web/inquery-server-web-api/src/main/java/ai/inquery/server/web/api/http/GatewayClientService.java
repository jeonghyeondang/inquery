package ai.inquery.server.web.api.http;

import ai.inquery.server.domain.api.model.VectorData;
import ai.inquery.server.domain.api.model.VectorSearchResult;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.config.InqueryProperties;
import ai.inquery.server.web.api.controller.ai.pinecone.bm25.BM25SparseEncoder;
import ai.inquery.server.web.api.http.model.TableSchema;
import ai.inquery.server.web.api.http.request.*;
import ai.inquery.server.web.api.http.response.*;
import ai.inquery.server.web.api.service.VectorStoreRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DataCatalogTableDO;
import ai.inquery.server.domain.repository.mapper.DataCatalogTableMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Gateway http service
 */
//@BaseRequest(
//    baseURL = "{gatewayBaseUrl}"
//)
@Slf4j
@Service
public class GatewayClientService {

    @Resource
    private InqueryProperties inqueryProperties;

    @Autowired
    private VectorStoreRegistry vectorStoreRegistry;

    /**
     * save knowledge vector
     *
     * @param request
     * @return
     */
    public ActionResult knowledgeVectorSave(KnowledgeRequest request) {
        // External gateway disabled for security
        return ActionResult.fail("External gateway disabled", "External gateway disabled for security", "GATEWAY_DISABLED");
    }

    /**
     * Save table schema vectors to the active vector store provider.
     */
    public ActionResult schemaVectorSave(TableSchemaRequest request) {
        VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();

        if (!provider.isConfigured()) {
            provider.refresh();
            if (!provider.isConfigured()) {
                log.warn("Vector store ({}) is not configured", provider.getType());
                return ActionResult.fail("Vector store not configured",
                        "Please configure " + provider.getType().getDisplayName() + " in Settings → Vector DB",
                        "VECTORDB_NOT_CONFIGURED");
            }
        }

        try {
            if (CollectionUtils.isEmpty(request.getSchemaList()) || CollectionUtils.isEmpty(request.getSchemaVector())) {
                return ActionResult.fail("Invalid request", "Schema list and vector are required", "INVALID_REQUEST");
            }

            String namespace = buildNamespace(request.getDbType());
            log.info("Saving vectors via {} - dataSourceId: {}, databaseName: {}, schemaName: {}, namespace: {}, vectorCount: {}",
                provider.getType(), request.getDataSourceId(), request.getDatabaseName(),
                request.getDataSourceSchema(), namespace, request.getSchemaVector().size());

            if (Boolean.TRUE.equals(request.getDeleteBeforeInsert())) {
                log.info("Delete before insert requested for namespace: {}", namespace);
            }

            // BM25 sparse encoder (Pinecone-specific hybrid search)
            BM25SparseEncoder bm25Encoder = null;
            if (provider.isHybridEnabled() && StringUtils.isNotBlank(provider.getHybridBm25Params())) {
                try {
                    bm25Encoder = new BM25SparseEncoder();
                    bm25Encoder.deserialize(provider.getHybridBm25Params());
                    log.info("BM25 encoder loaded for hybrid upsert");
                } catch (Exception e) {
                    log.warn("Failed to load BM25 encoder: {}", e.getMessage());
                    bm25Encoder = null;
                }
            }

            List<VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < request.getSchemaList().size() && i < request.getSchemaVector().size(); i++) {
                String schemaText = request.getSchemaList().get(i);
                List<BigDecimal> vector = request.getSchemaVector().get(i);

                List<Float> floatVector = vector.stream()
                    .map(BigDecimal::floatValue)
                    .collect(Collectors.toList());

                String vectorId;
                if (StringUtils.isNotBlank(request.getTableName()) && request.getSchemaList().size() == 1) {
                    vectorId = generateTableVectorId(request.getDatabaseName(),
                        request.getDataSourceSchema(), request.getTableName());
                } else {
                    vectorId = generateVectorId(request.getDataSourceId(), request.getDatabaseName(),
                        request.getDataSourceSchema(), i);
                }

                Map<String, String> metadata = new HashMap<>();
                metadata.put("dataSourceId", String.valueOf(request.getDataSourceId()));
                metadata.put("databaseName", request.getDatabaseName());
                metadata.put("schemaName", request.getDataSourceSchema());
                metadata.put("tableSchema", schemaText);
                if (StringUtils.isNotBlank(request.getTableName())) {
                    metadata.put("tableName", request.getTableName());
                }
                if (StringUtils.isNotBlank(request.getDbType())) {
                    metadata.put("dbType", request.getDbType());
                }

                String activeState = "true";
                if (StringUtils.isNotBlank(request.getTableName()) && Dbutils.hasSession()) {
                    try {
                        DataCatalogTableMapper catalogMapper = Dbutils.getMapper(DataCatalogTableMapper.class);
                        LambdaQueryWrapper<DataCatalogTableDO> wrapper = new LambdaQueryWrapper<>();
                        wrapper.eq(DataCatalogTableDO::getDataSourceId, request.getDataSourceId())
                                .eq(DataCatalogTableDO::getDatabaseName, request.getDatabaseName())
                                .eq(StringUtils.isNotBlank(request.getDataSourceSchema()),
                                        DataCatalogTableDO::getSchemaName, request.getDataSourceSchema())
                                .eq(DataCatalogTableDO::getTableName, request.getTableName());
                        DataCatalogTableDO catalogTable = catalogMapper.selectOne(wrapper);
                        if (catalogTable != null && catalogTable.getActive() != null) {
                            activeState = catalogTable.getActive() == 1 ? "true" : "false";
                        }
                    } catch (Exception dbEx) {
                        log.debug("Could not read active state from catalog: {}", dbEx.getMessage());
                    }
                }
                metadata.put("active", activeState);

                if (bm25Encoder != null) {
                    BM25SparseEncoder.SparseVector sparse = bm25Encoder.transform(schemaText);
                    if (!sparse.isEmpty()) {
                        vectors.add(new VectorData(vectorId, floatVector, metadata,
                            sparse.getIndices(), sparse.getValues()));
                        continue;
                    }
                }

                vectors.add(new VectorData(vectorId, floatVector, metadata));
            }

            log.info("Upserting {} vectors via {} to namespace: {}", vectors.size(), provider.getType(), namespace);
            boolean success = provider.upsert(vectors, namespace);
            if (success) {
                log.info("Successfully saved {} vectors via {}", vectors.size(), provider.getType());
                return ActionResult.isSuccess();
            } else {
                log.error("Failed to upsert vectors via {}", provider.getType());
                return ActionResult.fail("Failed to save vectors",
                        provider.getType() + " upsert failed", "VECTORDB_UPSERT_FAILED");
            }
        } catch (Exception e) {
            log.error("Failed to save vectors via {}", provider.getType(), e);
            return ActionResult.fail("Failed to save vectors", e.getMessage(), "VECTORDB_ERROR");
        }
    }

    private String buildNamespace(String dbType) {
        // Use dbType as namespace (e.g., "snowflake", "mysql") to match search namespace
        if (StringUtils.isNotBlank(dbType)) {
            return dbType.toLowerCase();
        }
        // Fallback to "snowflake" as default (most common case)
        return "snowflake";
    }

    private String generateVectorId(Long dataSourceId, String databaseName, String schemaName, int index) {
        return String.format("%d_%s_%s_%d_%d", 
            dataSourceId, 
            databaseName, 
            schemaName != null ? schemaName : "default",
            index,
            System.currentTimeMillis());
    }

    /**
     * Generate vector ID for a specific table (fixed ID, no timestamp, no dataSourceId)
     * This ensures that updating the same table will overwrite the existing vector
     */
    public static String generateTableVectorId(String databaseName, String schemaName, String tableName) {
        return String.format("%s_%s_%s", 
            databaseName, 
            schemaName != null ? schemaName : "default",
            tableName != null ? tableName : "unknown");
    }

    /**
     * save table schema vector
     *
     * @param request
     * @return
     */
    public ActionResult schemaEsSave(EsTableSchemaRequest request) {
        // External gateway disabled for security
        return ActionResult.fail("External gateway disabled", "External gateway disabled for security", "GATEWAY_DISABLED");
    }

    /**
     * save knowledge vector
     *
     * @param searchVectors
     * @return
     */
    public DataResult<KnowledgeResponse> knowledgeVectorSearch(KnowledgeRequest searchVectors) {
        // External gateway disabled for security
        return DataResult.of(null);
    }

    /**
     * Search table schema vectors from the active vector store provider.
     */
    public DataResult<TableSchemaResponse> schemaVectorSearch(TableSchemaRequest request) {
        VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
        if (!provider.isConfigured()) {
            provider.refresh();
            if (!provider.isConfigured()) {
                log.warn("Vector store ({}) is not configured", provider.getType());
                return DataResult.of(null);
            }
        }

        try {
            if (CollectionUtils.isEmpty(request.getSchemaVector())) {
                return DataResult.of(null);
            }

            List<BigDecimal> queryVectorBigDecimal = request.getSchemaVector().get(0);
            List<Float> queryVector = queryVectorBigDecimal.stream()
                .map(BigDecimal::floatValue)
                .collect(Collectors.toList());

            String namespace = buildNamespace(request.getDbType());

            Map<String, Object> filterMetadata = new HashMap<>();
            if (request.getDataSourceId() != null) {
                filterMetadata.put("dataSourceId", String.valueOf(request.getDataSourceId()));
            }
            if (StringUtils.isNotBlank(request.getDatabaseName())) {
                filterMetadata.put("databaseName", request.getDatabaseName());
            }
            if (StringUtils.isNotBlank(request.getDataSourceSchema())) {
                filterMetadata.put("schemaName", request.getDataSourceSchema());
            }

            List<VectorSearchResult> searchResults = provider.search(queryVector, 10, namespace, filterMetadata);

            List<TableSchema> tableSchemas = new ArrayList<>();
            for (VectorSearchResult result : searchResults) {
                TableSchema tableSchema = new TableSchema();
                try {
                    tableSchema.setId(Long.parseLong(result.getId().split("_")[0]));
                } catch (NumberFormatException e) {
                    tableSchema.setId(0L);
                }
                tableSchema.setDataSourceId(request.getDataSourceId());
                if (result.getMetadata() != null) {
                    tableSchema.setTableSchema(result.getMetadata().get("tableSchema"));
                }
                tableSchemas.add(tableSchema);
            }

            TableSchemaResponse response = new TableSchemaResponse();
            response.setTableSchemas(tableSchemas);
            return DataResult.of(response);
        } catch (Exception e) {
            log.error("Failed to search vectors via {}", provider.getType(), e);
            return DataResult.of(null);
        }
    }

    /**
     * save table schema vector
     *
     * @param request
     * @return
     */
    public DataResult<EsTableSchemaResponse> schemaEsSearch(EsTableSchemaRequest request) {
        // External gateway disabled for security
        return DataResult.of(null);
    }

    public DataResult<Boolean> checkInWhite(WhiteListRequest whiteListRequest) {
        return DataResult.of(false);
    }


}
