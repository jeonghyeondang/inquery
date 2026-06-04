
package ai.inquery.server.web.api.controller.config;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import ai.inquery.server.domain.api.enums.VectorStoreType;
import ai.inquery.server.domain.api.model.AIConfig;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.param.SystemConfigParam;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.domain.api.service.VectorStoreProvider;
import ai.inquery.server.web.api.service.VectorStoreRegistry;
import ai.inquery.server.domain.core.util.PermissionUtils;
import ai.inquery.server.domain.repository.entity.DataCatalogTableDO;
import ai.inquery.server.domain.repository.entity.DataCatalogColumnDO;
import ai.inquery.server.domain.repository.mapper.DataCatalogTableMapper;
import ai.inquery.server.domain.repository.mapper.DataCatalogColumnMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.ai.claude.client.ClaudeAIClient;
import ai.inquery.server.web.api.controller.ai.gemini.client.GeminiAIClient;
import ai.inquery.server.web.api.controller.config.request.AIConfigCreateRequest;
import ai.inquery.server.web.api.controller.config.request.SystemConfigRequest;
import ai.inquery.server.web.api.controller.ai.openai.client.OpenAIClient;
import ai.inquery.server.web.api.controller.ai.openai.client.OfficialOpenAIClient;
import ai.inquery.server.web.api.controller.ai.pinecone.client.PineconeClient;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@ConnectionInfoAspect
@RequestMapping("/api/config")
@RestController
@Slf4j
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @Autowired
    private TableService tableService;

    @Autowired
    private DataCatalogTableMapper dataCatalogTableMapper;

    @Autowired
    private DataCatalogColumnMapper dataCatalogColumnMapper;

    @Autowired
    private VectorStoreRegistry vectorStoreRegistry;

    /**
     * SSE chat (and the rest of the LangChain4j-based agents) cache built
     *   ChatModel/StreamingChatModel instances per modelName. Without an
     *   explicit invalidation, updating an API key here would only take
     *   effect after a server restart because the cached model still
     *   carries the old key. We Autowire the provider so we can clear
     *   that cache on every AI config write.
     */
    @Autowired
    private ai.inquery.server.domain.core.langchain.LangChainModelProvider langChainModelProvider;

    @PostMapping("/system_config")
    public ActionResult systemConfig(@RequestBody SystemConfigRequest request) {
        SystemConfigParam param = SystemConfigParam.builder().code(request.getCode()).content(request.getContent())
            .build();
        configService.createOrUpdate(param);
        if (OpenAIClient.OPENAI_KEY.equals(request.getCode())) {
            // Refresh OpenAI client when API key is updated
            Config apiKeyConfig = configService.find(OpenAIClient.OPENAI_KEY).getData();
            Config apiHostConfig = configService.find(OpenAIClient.OPENAI_HOST).getData();
            Config modelConfig = configService.find(OpenAIClient.OPENAI_MODEL).getData();
            
            String apiKey = apiKeyConfig != null ? apiKeyConfig.getContent() : "";
            String apiHost = apiHostConfig != null ? apiHostConfig.getContent() : "";
            String model = modelConfig != null ? modelConfig.getContent() : "gpt-5.4-mini";
            
            OfficialOpenAIClient.getInstance().refresh(apiKey, apiHost, model);
        }
        return ActionResult.isSuccess();
    }

    /**
     * Save ChatGPT related configurations
     *
     * @param request
     * @return
     */
    @PostMapping("/system_config/ai")
    public ActionResult addChatGptSystemConfig(@RequestBody AIConfigCreateRequest request) {
        PermissionUtils.checkDeskTopOrAdmin();
        
        log.info("Received AI config request - aiSqlSource: {}, apiKey: {}", 
            request.getAiSqlSource(), 
            request.getApiKey() != null && !request.getApiKey().isEmpty() ? "***" : "null");

        String sqlSource = request.getAiSqlSource();
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(sqlSource);
        if (Objects.isNull(aiSqlSourceEnum)) {
            sqlSource = AiSqlSourceEnum.OPENAI.getCode();
            aiSqlSourceEnum = AiSqlSourceEnum.OPENAI;
            log.warn("Unknown aiSqlSource: {}, defaulting to {}", request.getAiSqlSource(), sqlSource);
        }
        log.info("Processing AI config for: {}", aiSqlSourceEnum.name());
        
        SystemConfigParam param = SystemConfigParam.builder().code("ai.sql.source").content(sqlSource)
            .build();
        configService.createOrUpdate(param);

        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case OPENAI:
                saveOpenAIConfig(request);
                break;
            case CLAUDEAI:
                saveClaudeAIConfig(request);
                break;
            case GEMINI:
                saveGeminiConfig(request);
                break;
            default:
                log.warn("Unhandled AI source: {}", aiSqlSourceEnum);
                break;
        }
        // Drop the LangChain ChatModel/StreamingChatModel cache so the
        //   next chat request rebuilds the model with the freshly saved
        //   apiKey/host/model. Otherwise the cached client keeps using
        //   the prior credentials until the server restarts.
        try {
            langChainModelProvider.clearCache();
        } catch (Exception e) {
            log.warn("Failed to clear LangChain model cache after AI config update: {}", e.getMessage());
        }
        log.info("AI config saved successfully for: {}", aiSqlSourceEnum.name());
        return ActionResult.isSuccess();
    }

    /**
     * Persist the per-provider "enabled" flag if the request supplied one.
     * A null value means "leave alone" so older clients that don't send
     * the flag don't blindly enable/disable anything.
     */
    private void saveEnabledFlag(AIConfigCreateRequest request, String configKey) {
        if (request.getEnabled() == null) {
            return;
        }
        SystemConfigParam enabledParam = SystemConfigParam.builder()
            .code(configKey)
            .content(Boolean.TRUE.equals(request.getEnabled()) ? "true" : "false")
            .build();
        configService.createOrUpdate(enabledParam);
    }

    /**
     * Read the persisted "enabled" flag for a provider. Treat a missing or
     * blank row as enabled so providers configured before the flag existed
     * stay selectable.
     */
    private Boolean readEnabledFlag(String configKey) {
        DataResult<Config> r = configService.find(configKey);
        Config cfg = r != null ? r.getData() : null;
        if (cfg == null || StringUtils.isBlank(cfg.getContent())) {
            return Boolean.TRUE;
        }
        return !"false".equalsIgnoreCase(cfg.getContent().trim());
    }

    /**
     * save open ai config
     *
     * @param request
     */
    private void saveOpenAIConfig(AIConfigCreateRequest request) {
        SystemConfigParam param = SystemConfigParam.builder().code(OpenAIClient.OPENAI_KEY).content(
            request.getApiKey()).build();
        configService.createOrUpdate(param);
        SystemConfigParam hostParam = SystemConfigParam.builder().code(OpenAIClient.OPENAI_HOST).content(
            request.getApiHost()).build();
        configService.createOrUpdate(hostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(OpenAIClient.OPENAI_MODEL).content(
            request.getModel()).build();
        configService.createOrUpdate(modelParam);
        SystemConfigParam httpProxyHostParam = SystemConfigParam.builder().code(OpenAIClient.PROXY_HOST).content(
            request.getHttpProxyHost()).build();
        configService.createOrUpdate(httpProxyHostParam);
        SystemConfigParam httpProxyPortParam = SystemConfigParam.builder().code(OpenAIClient.PROXY_PORT).content(
            request.getHttpProxyPort()).build();
        configService.createOrUpdate(httpProxyPortParam);
        
        // Refresh Official OpenAI Client
        String apiKey = request.getApiKey();
        String apiHost = request.getApiHost();
        String model = request.getModel();
        if (StringUtils.isBlank(model)) {
            model = "gpt-5.4-mini";
        }
        OfficialOpenAIClient.getInstance().refresh(apiKey, apiHost, model);
        saveEnabledFlag(request, OpenAIClient.OPENAI_ENABLED);
    }

    /**
     * save claude ai config
     *
     * @param request
     */
    private void saveClaudeAIConfig(AIConfigCreateRequest request) {
        log.info("Saving Claude AI config - apiKey present: {}, apiHost: {}, model: {}", 
            request.getApiKey() != null && !request.getApiKey().isEmpty(),
            request.getApiHost(), request.getModel());
        
        SystemConfigParam apiKeyParam = SystemConfigParam.builder().code(ClaudeAIClient.CLAUDE_API_KEY).content(
                request.getApiKey()).build();
        configService.createOrUpdate(apiKeyParam);
        log.info("Claude API Key saved - code: {}", ClaudeAIClient.CLAUDE_API_KEY);
        
        SystemConfigParam hostParam = SystemConfigParam.builder().code(ClaudeAIClient.CLAUDE_API_HOST).content(
                request.getApiHost() != null ? request.getApiHost() : "https://api.anthropic.com").build();
        configService.createOrUpdate(hostParam);
        
        String model = request.getModel();
        if (model == null || model.isEmpty()) {
            // Stay aligned with the default surfaced by the settings UI
            //   and ChatController.DEFAULT_CLAUDE_MODEL.
            model = "claude-sonnet-4-6";
        }
        SystemConfigParam modelParam = SystemConfigParam.builder().code(ClaudeAIClient.CLAUDE_MODEL)
                .content(model).build();
        configService.createOrUpdate(modelParam);
        
        // Verify it was saved
        DataResult<Config> verify = configService.find(ClaudeAIClient.CLAUDE_API_KEY);
        log.info("Verified Claude API Key after save - exists: {}, length: {}", 
            verify.getData() != null,
            verify.getData() != null && verify.getData().getContent() != null ? verify.getData().getContent().length() : 0);
        
        ClaudeAIClient.refresh();
        saveEnabledFlag(request, ClaudeAIClient.CLAUDE_ENABLED);
    }

    /**
     * save gemini ai config
     *
     * @param request
     */
    private void saveGeminiConfig(AIConfigCreateRequest request) {
        SystemConfigParam apiKeyParam = SystemConfigParam.builder().code(GeminiAIClient.GEMINI_API_KEY).content(
                request.getApiKey()).build();
        configService.createOrUpdate(apiKeyParam);
        SystemConfigParam hostParam = SystemConfigParam.builder().code(GeminiAIClient.GEMINI_HOST).content(
                request.getApiHost()).build();
        configService.createOrUpdate(hostParam);
        SystemConfigParam modelParam = SystemConfigParam.builder().code(GeminiAIClient.GEMINI_MODEL)
                .content(request.getModel()).build();
        configService.createOrUpdate(modelParam);
        GeminiAIClient.getInstance().refresh(
            request.getApiKey(),
            request.getApiHost(),
            request.getModel()
        );
        saveEnabledFlag(request, GeminiAIClient.GEMINI_ENABLED);
    }

    @GetMapping("/system_config/{code}")
    public DataResult<Config> getSystemConfig(@PathVariable("code") String code) {
        DataResult<Config> result = configService.find(code);
        return DataResult.of(result.getData());
    }

    /**
     * ai config info
     *
     * @return
     */
    @GetMapping("/system_config/ai")
    public DataResult<AIConfig> getChatAiSystemConfig(String aiSqlSource) {
        DataResult<Config> dbSqlSource = configService.find("ai.sql.source");
        if (StringUtils.isBlank(aiSqlSource)) {
            if (Objects.nonNull(dbSqlSource.getData())) {
                aiSqlSource = dbSqlSource.getData().getContent();
            }
        }
        AIConfig config = new AIConfig();
        AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(aiSqlSource);
        if (Objects.isNull(aiSqlSourceEnum)) {
            aiSqlSource = AiSqlSourceEnum.OPENAI.getCode();
            config.setAiSqlSource(aiSqlSource);
            return DataResult.of(config);
        }
        config.setAiSqlSource(aiSqlSource);
        switch (Objects.requireNonNull(aiSqlSourceEnum)) {
            case OPENAI:
                DataResult<Config> apiKey = configService.find(OpenAIClient.OPENAI_KEY);
                DataResult<Config> apiHost = configService.find(OpenAIClient.OPENAI_HOST);
                DataResult<Config> httpProxyHost = configService.find(OpenAIClient.PROXY_HOST);
                DataResult<Config> httpProxyPort = configService.find(OpenAIClient.PROXY_PORT);
                config.setApiKey(Objects.nonNull(apiKey.getData()) ? apiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(apiHost.getData()) ? apiHost.getData().getContent() : "");
                config.setHttpProxyHost(
                    Objects.nonNull(httpProxyHost.getData()) ? httpProxyHost.getData().getContent() : "");
                config.setHttpProxyPort(
                    Objects.nonNull(httpProxyPort.getData()) ? httpProxyPort.getData().getContent() : "");
                config.setEnabled(readEnabledFlag(OpenAIClient.OPENAI_ENABLED));
                break;
            case CLAUDEAI:
                DataResult<Config> claudeApiKey = configService.find(ClaudeAIClient.CLAUDE_API_KEY);
                DataResult<Config> claudeApiHost = configService.find(ClaudeAIClient.CLAUDE_API_HOST);
                DataResult<Config> claudeModel = configService.find(ClaudeAIClient.CLAUDE_MODEL);
                
                log.info("Claude AI config fetch - API Key exists: {}, Host exists: {}, Model exists: {}", 
                    claudeApiKey.getData() != null, 
                    claudeApiHost.getData() != null, 
                    claudeModel.getData() != null);
                
                String claudeApiKeyValue = "";
                if (claudeApiKey.getData() != null) {
                    String content = claudeApiKey.getData().getContent();
                    log.info("Claude API Key content: {} (length: {})", 
                        content != null ? (content.length() > 10 ? content.substring(0, 10) + "..." : content) : "null",
                        content != null ? content.length() : 0);
                    claudeApiKeyValue = content != null ? content : "";
                } else {
                    log.warn("Claude API Key config not found in database!");
                }
                
                config.setApiKey(claudeApiKeyValue);
                config.setApiHost(Objects.nonNull(claudeApiHost.getData()) ? claudeApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(claudeModel.getData()) ? claudeModel.getData().getContent() : "");
                
                log.info("Claude AI config set - apiKey length: {}, apiHost: {}, model: {}", 
                    claudeApiKeyValue.length(), 
                    config.getApiHost(), 
                    config.getModel());
                config.setEnabled(readEnabledFlag(ClaudeAIClient.CLAUDE_ENABLED));
                break;
            case GEMINI:
                DataResult<Config> geminiApiKey = configService.find(GeminiAIClient.GEMINI_API_KEY);
                DataResult<Config> geminiApiHost = configService.find(GeminiAIClient.GEMINI_HOST);
                DataResult<Config> geminiModel = configService.find(GeminiAIClient.GEMINI_MODEL);
                config.setApiKey(Objects.nonNull(geminiApiKey.getData()) ? geminiApiKey.getData().getContent() : "");
                config.setApiHost(Objects.nonNull(geminiApiHost.getData()) ? geminiApiHost.getData().getContent() : "");
                config.setModel(Objects.nonNull(geminiModel.getData()) ? geminiModel.getData().getContent() : "");
                config.setEnabled(readEnabledFlag(GeminiAIClient.GEMINI_ENABLED));
                break;
            default:
                break;
        }
        
        log.info("Returning AI config - aiSqlSource: {}, apiKey length: {}", 
            config.getAiSqlSource(), 
            config.getApiKey() != null ? config.getApiKey().length() : 0);

        return DataResult.of(config);
    }

    /**
     * Get Pinecone configuration
     *
     * @return Pinecone config
     */
    @GetMapping("/pinecone")
    public DataResult<Map<String, String>> getPineconeConfig() {
        Map<String, String> config = new HashMap<>();
        DataResult<Config> apiKey = configService.find(PineconeClient.PINECONE_API_KEY);
        DataResult<Config> host = configService.find(PineconeClient.PINECONE_HOST);
        DataResult<Config> indexName = configService.find(PineconeClient.PINECONE_INDEX_NAME);
        DataResult<Config> namespace = configService.find(PineconeClient.PINECONE_NAMESPACE);
        
        config.put("apiKey", Objects.nonNull(apiKey.getData()) ? apiKey.getData().getContent() : "");
        config.put("host", Objects.nonNull(host.getData()) ? host.getData().getContent() : "");
        config.put("indexName", Objects.nonNull(indexName.getData()) ? indexName.getData().getContent() : "table-schemas");
        config.put("namespace", Objects.nonNull(namespace.getData()) ? namespace.getData().getContent() : "default");
        
        return DataResult.of(config);
    }

    /**
     * Save Pinecone configuration
     *
     * @param request Pinecone config request
     * @return action result
     */
    @PostMapping("/pinecone")
    public ActionResult savePineconeConfig(@RequestBody Map<String, String> request) {
        PermissionUtils.checkDeskTopOrAdmin();
        
        log.info("Received Pinecone config save request - apiKey present: {}, host: {}, indexName: {}, namespace: {}", 
            request.get("apiKey") != null && !request.get("apiKey").isEmpty(),
            request.get("host"),
            request.get("indexName"),
            request.get("namespace"));
        
        try {
            String apiKey = request.get("apiKey");
            String host = request.get("host");
            String indexName = request.get("indexName");
            String namespace = request.get("namespace");
            
            // API Key is required
            if (StringUtils.isBlank(apiKey)) {
                log.warn("Pinecone API key is required but not provided");
                return ActionResult.fail("API Key is required", "Pinecone API key is required", "PINECONE_API_KEY_REQUIRED");
            }
            
            // Save API Key
            SystemConfigParam apiKeyParam = SystemConfigParam.builder()
                .code(PineconeClient.PINECONE_API_KEY)
                .content(apiKey)
                .build();
            configService.createOrUpdate(apiKeyParam);
            log.info("Pinecone API key saved");
            
            // Save host if provided
            if (StringUtils.isNotBlank(host)) {
                SystemConfigParam hostParam = SystemConfigParam.builder()
                    .code(PineconeClient.PINECONE_HOST)
                    .content(host)
                    .build();
                configService.createOrUpdate(hostParam);
                log.info("Pinecone host saved: {}", host);
            }
            
            // Save index name if provided
            if (StringUtils.isNotBlank(indexName)) {
                SystemConfigParam indexNameParam = SystemConfigParam.builder()
                    .code(PineconeClient.PINECONE_INDEX_NAME)
                    .content(indexName)
                    .build();
                configService.createOrUpdate(indexNameParam);
                log.info("Pinecone index name saved: {}", indexName);
            }
            
            // Save namespace if provided
            if (StringUtils.isNotBlank(namespace)) {
                SystemConfigParam namespaceParam = SystemConfigParam.builder()
                    .code(PineconeClient.PINECONE_NAMESPACE)
                    .content(namespace)
                    .build();
                configService.createOrUpdate(namespaceParam);
                log.info("Pinecone namespace saved: {}", namespace);
            }
            
            // Refresh Pinecone client
            try {
                PineconeClient.refresh();
                log.info("Pinecone client refreshed successfully");
            } catch (Exception e) {
                log.error("Failed to refresh Pinecone client", e);
                // Don't fail the save operation if refresh fails
                // The client will be refreshed on next use
            }
            
            log.info("Pinecone config saved successfully");
            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("Failed to save Pinecone configuration", e);
            // Sanitize error message to prevent JSON parsing issues
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.isEmpty()) {
                errorMessage = "Unknown error occurred";
            } else {
                // Remove newlines and escape special characters that might break JSON
                errorMessage = errorMessage.replace("\n", " ").replace("\r", " ").trim();
                // Limit message length to prevent extremely long error messages
                if (errorMessage.length() > 500) {
                    errorMessage = errorMessage.substring(0, 500) + "...";
                }
            }
            return ActionResult.fail("Failed to save configuration", errorMessage, "PINECONE_SAVE_ERROR");
        }
    }

    /**
     * Get Pinecone index statistics
     *
     * @return Index stats
     */
    @GetMapping("/pinecone/stats")
    public DataResult<Map<String, Object>> getPineconeStats() {
        try {
            VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
            if (!provider.isConfigured()) {
                provider.refresh();
                if (!provider.isConfigured()) {
                    return DataResult.error("VECTORDB_NOT_CONFIGURED", "Vector store is not configured");
                }
            }

            Map<String, Object> result = provider.getStats();
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to get vector store stats", e);
            return DataResult.error("VECTORDB_STATS_ERROR", "Failed to get stats: " + e.getMessage());
        }
    }

    /**
     * Sync tables from Pinecone to local DB (TableCache)
     * This fetches metadata from Pinecone vectors and creates corresponding TableCache entries
     *
     * @param request Optional request body with namespace override
     * @return Sync result with counts
     */
    @PostMapping("/pinecone/sync-from-pinecone")
    public DataResult<Map<String, Object>> syncFromPinecone(@RequestBody(required = false) Map<String, String> request) {
        PermissionUtils.checkDeskTopOrAdmin();
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
            if (!provider.isConfigured()) {
                provider.refresh();
                if (!provider.isConfigured()) {
                    return DataResult.error("VECTORDB_NOT_CONFIGURED", "Vector store is not configured.");
                }
            }

            String namespace = request != null ? request.get("namespace") : null;
            
            log.info("Starting sync from {} to local DB, namespace: {}", provider.getType(), namespace != null ? namespace : "default");
            
            log.info("Step 1: Listing all vectors...");
            List<String> vectorIds = provider.listAllVectors(namespace);
            log.info("Found {} vectors", vectorIds.size());
            
            if (vectorIds.isEmpty()) {
                result.put("success", true);
                result.put("message", "No vectors found");
                result.put("totalVectors", 0);
                result.put("synced", 0);
                result.put("skipped", 0);
                return DataResult.of(result);
            }
            
            log.info("Step 2: Fetching vector metadata...");
            List<ai.inquery.server.domain.api.model.VectorData> vectors = provider.fetchVectors(vectorIds, namespace);
            log.info("Fetched metadata for {} vectors", vectors.size());
            
            log.info("Step 3: Processing and saving to DataCatalog...");
            int synced = 0;
            int skipped = 0;
            int errors = 0;
            Set<String> processedKeys = new HashSet<>();
            
            for (ai.inquery.server.domain.api.model.VectorData vector : vectors) {
                try {
                    Map<String, String> metadata = vector.getMetadata();
                    if (metadata == null || metadata.isEmpty()) {
                        log.warn("Vector {} has no metadata, skipping", vector.getId());
                        skipped++;
                        continue;
                    }
                    
                    // Extract required fields from metadata
                    String dataSourceIdStr = metadata.get("dataSourceId");
                    String databaseName = metadata.get("databaseName");
                    String schemaName = metadata.get("schemaName");
                    String tableName = metadata.get("tableName");
                    String tableSchema = metadata.get("tableSchema");
                    
                    if (StringUtils.isBlank(dataSourceIdStr) || StringUtils.isBlank(tableName)) {
                        log.warn("Vector {} missing required metadata (dataSourceId or tableName), skipping", vector.getId());
                        skipped++;
                        continue;
                    }
                    
                    Long dataSourceId = Long.parseLong(dataSourceIdStr);
                    
                    // Create unique key for this table
                    String key = dataSourceId + "_" + 
                        (databaseName != null ? databaseName : "") + "_" + 
                        (schemaName != null ? schemaName : "") + "_" + 
                        tableName;
                    
                    // Skip if already processed in this batch
                    if (processedKeys.contains(key)) {
                        skipped++;
                        continue;
                    }
                    processedKeys.add(key);
                    
                    // Check if already exists in DataCatalogTable
                    LambdaQueryWrapper<DataCatalogTableDO> queryWrapper = new LambdaQueryWrapper<>();
                    queryWrapper.eq(DataCatalogTableDO::getDataSourceId, dataSourceId)
                        .eq(databaseName != null, DataCatalogTableDO::getDatabaseName, databaseName)
                        .eq(schemaName != null, DataCatalogTableDO::getSchemaName, schemaName)
                        .eq(DataCatalogTableDO::getTableName, tableName)
                        .last("LIMIT 1");
                    
                    List<DataCatalogTableDO> existingList = dataCatalogTableMapper.selectList(queryWrapper);
                    
                    // Extract description from tableSchema
                    String tableDescription = "";
                    if (StringUtils.isNotBlank(tableSchema)) {
                        String desc = extractDescriptionFromSchema(tableSchema);
                        if (desc != null) {
                            tableDescription = desc;
                        }
                    }
                    
                    DataCatalogTableDO tableDO;
                    if (!existingList.isEmpty()) {
                        // Update existing table
                        tableDO = existingList.get(0);
                        if (StringUtils.isNotBlank(tableDescription) && StringUtils.isBlank(tableDO.getTableDescription())) {
                            tableDO.setTableDescription(tableDescription);
                            tableDO.setGmtModified(new Date());
                            dataCatalogTableMapper.updateById(tableDO);
                            log.debug("Updated table description for {}", key);
                        } else {
                            log.debug("Table {} already exists with description, skipping", key);
                        }
                    } else {
                        // Create new DataCatalogTable entry
                        tableDO = new DataCatalogTableDO();
                        tableDO.setDataSourceId(dataSourceId);
                        tableDO.setDatabaseName(databaseName);
                        tableDO.setSchemaName(schemaName);
                        tableDO.setTableName(tableName);
                        tableDO.setTableDescription(tableDescription);
                        tableDO.setActive((short) 1);
                        tableDO.setGmtCreate(new Date());
                        tableDO.setGmtModified(new Date());
                        dataCatalogTableMapper.insert(tableDO);
                        log.debug("Inserted new table {}", key);
                    }
                    
                    // Extract and save columns from tableSchema
                    if (StringUtils.isNotBlank(tableSchema) && tableDO.getId() != null) {
                        List<Map<String, Object>> columns = extractColumnsFromSchemaAsList(tableSchema);
                        if (columns != null && !columns.isEmpty()) {
                            int position = 0;
                            for (Map<String, Object> col : columns) {
                                String columnName = (String) col.get("name");
                                if (StringUtils.isBlank(columnName)) continue;
                                
                                // Check if column already exists
                                LambdaQueryWrapper<DataCatalogColumnDO> colQuery = new LambdaQueryWrapper<>();
                                colQuery.eq(DataCatalogColumnDO::getTableId, tableDO.getId())
                                    .eq(DataCatalogColumnDO::getColumnName, columnName)
                                    .last("LIMIT 1");
                                List<DataCatalogColumnDO> existingCols = dataCatalogColumnMapper.selectList(colQuery);
                                
                                String columnDescription = (String) col.get("description");
                                String columnType = (String) col.get("type");
                                Boolean nullable = (Boolean) col.get("nullable");
                                
                                // Build schemaInfo JSON
                                Map<String, Object> schemaInfo = new HashMap<>();
                                if (columnType != null) schemaInfo.put("type", columnType);
                                if (nullable != null) schemaInfo.put("nullable", nullable);
                                String schemaInfoJson = null;
                                try {
                                    schemaInfoJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(schemaInfo);
                                } catch (Exception e) {
                                    log.warn("Failed to serialize schemaInfo for column {}", columnName);
                                }
                                
                                if (!existingCols.isEmpty()) {
                                    // Update if description is empty
                                    DataCatalogColumnDO existingCol = existingCols.get(0);
                                    if (StringUtils.isNotBlank(columnDescription) && StringUtils.isBlank(existingCol.getColumnDescription())) {
                                        existingCol.setColumnDescription(columnDescription);
                                        existingCol.setSchemaInfo(schemaInfoJson);
                                        existingCol.setOrdinalPosition(position);
                                        existingCol.setGmtModified(new Date());
                                        dataCatalogColumnMapper.updateById(existingCol);
                                    }
                                } else {
                                    // Insert new column
                                    DataCatalogColumnDO columnDO = new DataCatalogColumnDO();
                                    columnDO.setTableId(tableDO.getId());
                                    columnDO.setColumnName(columnName);
                                    columnDO.setColumnDescription(columnDescription != null ? columnDescription : "");
                                    columnDO.setSchemaInfo(schemaInfoJson);
                                    columnDO.setOrdinalPosition(position);
                                    columnDO.setGmtCreate(new Date());
                                    columnDO.setGmtModified(new Date());
                                    dataCatalogColumnMapper.insert(columnDO);
                                }
                                position++;
                            }
                        }
                    }
                    
                    synced++;
                    
                } catch (Exception e) {
                    log.error("Error processing vector {}: {}", vector.getId(), e.getMessage());
                    errors++;
                }
            }
            
            result.put("success", true);
            result.put("message", String.format("Synced %d tables from vector store", synced));
            result.put("totalVectors", vectorIds.size());
            result.put("synced", synced);
            result.put("skipped", skipped);
            result.put("errors", errors);
            
            log.info("Sync completed - total: {}, synced: {}, skipped: {}, errors: {}", 
                vectorIds.size(), synced, skipped, errors);
            
            return DataResult.of(result);
            
        } catch (Exception e) {
            log.error("Failed to sync from vector store", e);
            return DataResult.error("VECTORDB_SYNC_ERROR", "Failed to sync: " + e.getMessage());
        }
    }
    
    /**
     * Extract description from tableSchema text
     * Format: "Table: NAME\nDescription: xxx\n..."
     */
    private String extractDescriptionFromSchema(String tableSchema) {
        if (StringUtils.isBlank(tableSchema)) {
            return null;
        }
        
        try {
            // Look for "Description: " line
            int descStart = tableSchema.indexOf("Description:");
            if (descStart == -1) {
                return null;
            }
            
            int lineEnd = tableSchema.indexOf("\n", descStart);
            if (lineEnd == -1) {
                lineEnd = tableSchema.length();
            }
            
            String description = tableSchema.substring(descStart + "Description:".length(), lineEnd).trim();
            return description.isEmpty() ? null : description;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extract columns from tableSchema text and convert to List
     * Format: "Columns:\n - COL_NAME (TYPE) [NULLABLE]: description\n..."
     */
    private List<Map<String, Object>> extractColumnsFromSchemaAsList(String tableSchema) {
        if (StringUtils.isBlank(tableSchema)) {
            return null;
        }
        
        try {
            int columnsStart = tableSchema.indexOf("Columns:");
            if (columnsStart == -1) {
                return null;
            }
            
            String columnsSection = tableSchema.substring(columnsStart + "Columns:".length());
            String[] lines = columnsSection.split("\n");
            
            List<Map<String, Object>> columns = new ArrayList<>();
            
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("-")) {
                    continue;
                }
                
                // Parse line format: " - COL_NAME (TYPE) [NULLABLE]: description"
                line = line.substring(1).trim(); // Remove leading "-"
                
                Map<String, Object> column = new HashMap<>();
                
                // Extract column name
                int parenStart = line.indexOf("(");
                if (parenStart > 0) {
                    column.put("name", line.substring(0, parenStart).trim());
                    
                    // Extract type
                    int parenEnd = line.indexOf(")", parenStart);
                    if (parenEnd > parenStart) {
                        column.put("type", line.substring(parenStart + 1, parenEnd).trim());
                        
                        // Extract nullable
                        int bracketStart = line.indexOf("[", parenEnd);
                        int bracketEnd = line.indexOf("]", bracketStart);
                        if (bracketStart > 0 && bracketEnd > bracketStart) {
                            String nullable = line.substring(bracketStart + 1, bracketEnd).trim();
                            column.put("nullable", "NULLABLE".equalsIgnoreCase(nullable));
                        }
                        
                        // Extract description (after ":")
                        int colonPos = line.indexOf(":", parenEnd);
                        if (colonPos > 0 && colonPos < line.length() - 1) {
                            String desc = line.substring(colonPos + 1).trim();
                            // Remove [Examples: ...] part
                            int examplesPos = desc.indexOf("[Examples:");
                            if (examplesPos > 0) {
                                desc = desc.substring(0, examplesPos).trim();
                            }
                            if (!desc.isEmpty()) {
                                column.put("description", desc);
                            }
                        }
                    }
                } else {
                    // Simple format without type
                    column.put("name", line);
                }
                
                if (column.containsKey("name")) {
                    columns.add(column);
                }
            }
            
            return columns.isEmpty() ? null : columns;
        } catch (Exception e) {
            log.warn("Failed to parse columns from tableSchema: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Test AI API key validity
     * Makes a simple API call to verify the key works
     */
    @PostMapping("/ai/test")
    public DataResult<Map<String, Object>> testAiApiKey(@RequestBody AIConfigCreateRequest request) {
        Map<String, Object> result = new HashMap<>();
        String aiSqlSource = request.getAiSqlSource();
        String apiKey = request.getApiKey();
        String apiHost = request.getApiHost();
        String model = request.getModel();
        
        if (StringUtils.isBlank(apiKey)) {
            return DataResult.error("INVALID_KEY", "API key is required");
        }
        
        try {
            AiSqlSourceEnum aiSqlSourceEnum = AiSqlSourceEnum.getByName(aiSqlSource);
            if (aiSqlSourceEnum == null) {
                return DataResult.error("INVALID_PROVIDER", "Unknown AI provider: " + aiSqlSource);
            }
            
            switch (aiSqlSourceEnum) {
                case OPENAI:
                    result = testOpenAIKey(apiKey, apiHost, model);
                    break;
                case CLAUDEAI:
                    result = testClaudeKey(apiKey, apiHost, model);
                    break;
                case GEMINI:
                    result = testGeminiKey(apiKey, apiHost, model);
                    break;
                default:
                    return DataResult.error("UNSUPPORTED", "API key testing not supported for: " + aiSqlSource);
            }
            
            return DataResult.of(result);
        } catch (Exception e) {
            log.error("Failed to test AI API key for {}: {}", aiSqlSource, e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return DataResult.of(result);
        }
    }
    
    private Map<String, Object> testOpenAIKey(String apiKey, String apiHost, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            String host = StringUtils.isNotBlank(apiHost) ? apiHost : "https://api.openai.com";
            if (host.endsWith("/")) host = host.substring(0, host.length() - 1);

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

            // Hit /v1/chat/completions instead of /v1/models so the request
            //   actually consumes billing quota and surfaces
            //   `insufficient_quota` during config Test (mirrors how the
            //   Claude test uses /v1/messages). max_tokens=1 keeps the
            //   cost negligible (~1 input + 1 output token).
            //
            // Default model `gpt-4o-mini` is the cheapest broadly-available
            //   chat model; new accounts almost always have access. The
            //   user's chosen `model` (if provided) is preferred so the
            //   test exercises the same SKU as actual chat traffic. The
            //   OpenAI settings card currently doesn't expose the model
            //   field, so this falls through to the default in practice.
            String testModel = StringUtils.isNotBlank(model) ? model : "gpt-4o-mini";
            String escapedModel = testModel.replace("\"", "\\\"");
            // Reasoning-style OpenAI models (gpt-5*, o1*, o3*, o4*) reject
            //   `max_tokens` and require `max_completion_tokens` instead.
            //   Mirrors LangChainModelProvider.isOpenAiReasoningModel.
            String lower = testModel.toLowerCase();
            boolean reasoning = lower.startsWith("gpt-5") || lower.startsWith("o1")
                || lower.startsWith("o3") || lower.startsWith("o4");
            String tokenField = reasoning ? "max_completion_tokens" : "max_tokens";
            String jsonBody = "{\"model\":\"" + escapedModel + "\","
                + "\"" + tokenField + "\":1,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";

            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(host + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(jsonBody, okhttp3.MediaType.parse("application/json")))
                .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    result.put("success", true);
                    result.put("message", "OpenAI API key is valid (quota check passed)");
                } else {
                    result.put("success", false);
                    // Surface a friendlier message for the most common
                    //   failure modes so users don't have to grep raw JSON.
                    String friendly;
                    if (body.contains("insufficient_quota")) {
                        friendly = "OpenAI: insufficient quota — add billing/credits in the OpenAI dashboard before using this key.";
                    } else if (response.code() == 401) {
                        friendly = "OpenAI: invalid API key (401).";
                    } else if (body.contains("model_not_found") || (response.code() == 404 && body.contains("model"))) {
                        friendly = "OpenAI: model `" + testModel + "` not available on this key.";
                    } else {
                        friendly = "OpenAI API error: " + response.code() + " - " + body;
                    }
                    result.put("message", friendly);
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to connect to OpenAI: " + e.getMessage());
        }
        return result;
    }
    
    private Map<String, Object> testClaudeKey(String apiKey, String apiHost, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            String host = StringUtils.isNotBlank(apiHost) ? apiHost : "https://api.anthropic.com";
            if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
            
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
            
            // Simple message request to test the key. Default is kept
            //   in sync with ChatController.DEFAULT_CLAUDE_MODEL and the
            //   recommendation shown in the settings UI.
            String testModel = StringUtils.isNotBlank(model) ? model : "claude-sonnet-4-6";
            String jsonBody = "{\"model\":\"" + testModel + "\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
            
            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(host + "/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(jsonBody, okhttp3.MediaType.parse("application/json")))
                .build();
            
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    result.put("success", true);
                    result.put("message", "Claude API key is valid");
                } else {
                    result.put("success", false);
                    String body = response.body() != null ? response.body().string() : "";
                    result.put("message", "Claude API error: " + response.code() + " - " + body);
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to connect to Claude: " + e.getMessage());
        }
        return result;
    }
    
    private Map<String, Object> testGeminiKey(String apiKey, String apiHost, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            String host = StringUtils.isNotBlank(apiHost) ? apiHost : "https://generativelanguage.googleapis.com/v1beta/";
            if (!host.endsWith("/")) host += "/";
            
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
            
            // List models to test the key
            okhttp3.Request request = new okhttp3.Request.Builder()
                .url(host + "models?key=" + apiKey)
                .get()
                .build();
            
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    result.put("success", true);
                    result.put("message", "Gemini API key is valid");
                } else {
                    result.put("success", false);
                    String body = response.body() != null ? response.body().string() : "";
                    result.put("message", "Gemini API error: " + response.code() + " - " + body);
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to connect to Gemini: " + e.getMessage());
        }
        return result;
    }

    // ── Vector DB Provider Config APIs ─────────────────────────

    @GetMapping("/vectordb/type")
    public DataResult<Map<String, Object>> getVectorDbType() {
        VectorStoreType type = vectorStoreRegistry.getActiveType();
        Map<String, Object> result = new HashMap<>();
        result.put("type", type.getCode());
        result.put("displayName", type.getDisplayName());
        result.put("description", type.getDescription());
        return DataResult.of(result);
    }

    @PostMapping("/vectordb/type")
    public ActionResult setVectorDbType(@RequestBody Map<String, String> request) {
        PermissionUtils.checkDeskTopOrAdmin();
        String type = request.get("type");
        if (StringUtils.isBlank(type)) {
            return ActionResult.fail("Type required", "vectordb type is required", "INVALID_REQUEST");
        }

        VectorStoreType storeType = VectorStoreType.fromCode(type);
        SystemConfigParam param = SystemConfigParam.builder()
                .code(VectorStoreRegistry.CONFIG_VECTORDB_TYPE)
                .content(storeType.getCode())
                .build();
        configService.createOrUpdate(param);
        log.info("Vector DB type changed to: {}", storeType);
        return ActionResult.isSuccess();
    }

    @GetMapping("/vectordb/providers")
    public DataResult<List<Map<String, Object>>> getVectorDbProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();
        VectorStoreType activeType = vectorStoreRegistry.getActiveType();

        for (Map.Entry<VectorStoreType, VectorStoreProvider> entry :
                vectorStoreRegistry.getAllProviders().entrySet()) {
            Map<String, Object> info = new HashMap<>();
            info.put("type", entry.getKey().getCode());
            info.put("displayName", entry.getKey().getDisplayName());
            info.put("description", entry.getKey().getDescription());
            info.put("configured", entry.getValue().isConfigured());
            info.put("active", entry.getKey() == activeType);
            providers.add(info);
        }
        return DataResult.of(providers);
    }

    @GetMapping("/vectordb/stats")
    public DataResult<Map<String, Object>> getVectorDbStats() {
        VectorStoreProvider provider = vectorStoreRegistry.getActiveProvider();
        Map<String, Object> stats = provider.getStats();
        stats.put("providerType", provider.getType().getCode());
        stats.put("providerName", provider.getType().getDisplayName());
        return DataResult.of(stats);
    }

    @PostMapping("/vectordb/test")
    public DataResult<Map<String, Object>> testVectorDbConnection(@RequestBody Map<String, String> request) {
        String type = request.get("type");
        VectorStoreType storeType = VectorStoreType.fromCode(type);
        VectorStoreProvider provider = vectorStoreRegistry.getProvider(storeType);

        Map<String, Object> result = new HashMap<>();
        if (provider == null) {
            result.put("success", false);
            result.put("message", "Provider not found: " + type);
            return DataResult.of(result);
        }

        try {
            if (!provider.isConfigured()) {
                provider.refresh();
            }
            boolean configured = provider.isConfigured();
            if (!configured) {
                result.put("success", false);
                result.put("message", storeType.getDisplayName() + " is not configured. Please save the configuration first.");
                return DataResult.of(result);
            }

            provider.ensureConnection();

            try {
                Map<String, Object> stats = provider.getStats();
                if (stats.containsKey("error")) {
                    result.put("success", false);
                    result.put("message", "Connection failed: " + stats.get("error"));
                } else {
                    result.put("success", true);
                    result.put("message", storeType.getDisplayName() + " connection successful");
                    result.putAll(stats);
                }
            } catch (Exception statsEx) {
                log.warn("Vector DB test - getStats failed for {}: {}", storeType, statsEx.getMessage());
                result.put("success", true);
                result.put("message", storeType.getDisplayName() + " configured (stats unavailable: " + statsEx.getMessage() + ")");
            }
        } catch (Exception e) {
            log.error("Vector DB test failed for {}: {}", storeType, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "Connection failed: " + e.getMessage());
        }
        return DataResult.of(result);
    }

    @GetMapping("/qdrant")
    public DataResult<Map<String, String>> getQdrantConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("host", getConfigValue("qdrant.host", ""));
        config.put("port", getConfigValue("qdrant.port", "6334"));
        config.put("apiKey", getConfigValue("qdrant.apiKey", ""));
        config.put("collectionName", getConfigValue("qdrant.collectionName", "table-schemas"));
        config.put("useTls", getConfigValue("qdrant.useTls", "false"));
        return DataResult.of(config);
    }

    @PostMapping("/qdrant")
    public ActionResult saveQdrantConfig(@RequestBody Map<String, String> request) {
        PermissionUtils.checkDeskTopOrAdmin();
        try {
            String host = request.get("host");
            if (StringUtils.isBlank(host)) {
                return ActionResult.fail("Host required", "Qdrant host is required", "QDRANT_HOST_REQUIRED");
            }

            saveConfig("qdrant.host", host);
            saveConfig("qdrant.port", request.getOrDefault("port", "6334"));
            if (StringUtils.isNotBlank(request.get("apiKey"))) {
                saveConfig("qdrant.apiKey", request.get("apiKey"));
            }
            saveConfig("qdrant.collectionName", request.getOrDefault("collectionName", "table-schemas"));
            saveConfig("qdrant.useTls", request.getOrDefault("useTls", "false"));

            VectorStoreProvider qdrant = vectorStoreRegistry.getProvider(VectorStoreType.QDRANT);
            if (qdrant != null) qdrant.refresh();

            return ActionResult.isSuccess();
        } catch (Exception e) {
            log.error("Failed to save Qdrant config", e);
            return ActionResult.fail("Failed to save", e.getMessage(), "QDRANT_SAVE_ERROR");
        }
    }

    private String getConfigValue(String code, String defaultValue) {
        try {
            DataResult<Config> r = configService.find(code);
            if (r != null && r.getData() != null && StringUtils.isNotBlank(r.getData().getContent())) {
                return r.getData().getContent();
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private void saveConfig(String code, String value) {
        if (value == null) return;
        SystemConfigParam param = SystemConfigParam.builder().code(code).content(value).build();
        configService.createOrUpdate(param);
    }

}
