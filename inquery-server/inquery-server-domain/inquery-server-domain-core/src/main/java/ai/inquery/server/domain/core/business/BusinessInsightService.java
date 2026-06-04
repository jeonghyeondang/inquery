package ai.inquery.server.domain.core.business;

import ai.inquery.server.domain.api.model.BusinessInsightDTO;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.core.langchain.LangChainModelProvider;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService.SearchResult;
import ai.inquery.server.domain.core.langchain.tools.WebSearchService.WebSearchResponse;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.DatabaseBusinessInsightDO;
import ai.inquery.server.domain.repository.mapper.DatabaseBusinessInsightMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Business insight CRUD + AI generation.
 *
 * <p>Insight generation no longer talks to Gemini directly. It delegates to
 * {@link WebSearchService}, which dispatches to whichever provider the user
 * has an API key for (OpenAI {@code web_search} / Anthropic
 * {@code web_search_20250305} / Gemini {@code google_search} grounding). The
 * old implementation hard-coded {@code com.google.genai.Client} and threw
 * {@code "Gemini API key not configured"} even when the user had a perfectly
 * usable OpenAI / Claude key in Settings &gt; AI Chat.
 */
@Slf4j
@Service
public class BusinessInsightService {

    @Autowired
    private ConfigService configService;

    @Autowired
    private WebSearchService webSearchService;

    // Fallback defaults per provider. Match ChatController /
    // DeepResearchController to keep behavior consistent across the app
    // when the user hasn't pinned a specific chat model.
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.4-mini";
    private static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-6";

    // Config keys for the user's chosen per-provider chat model.
    // Mirrors OpenAIClient.OPENAI_MODEL / ClaudeAIClient.CLAUDE_MODEL /
    // GeminiAIClient.GEMINI_MODEL - duplicated as string constants here to
    // avoid pulling the web-layer client classes into the domain module.
    private static final String OPENAI_MODEL_KEY = "chatgpt.model";
    private static final String CLAUDE_MODEL_KEY = "claude.model";
    private static final String GEMINI_MODEL_KEY = "gemini.model";

    private DatabaseBusinessInsightMapper getMapper() {
        return Dbutils.getMapper(DatabaseBusinessInsightMapper.class);
    }

    public BusinessInsightDTO getInsight(Long dataSourceId, String databaseName) {
        LambdaQueryWrapper<DatabaseBusinessInsightDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DatabaseBusinessInsightDO::getDataSourceId, dataSourceId)
                .eq(DatabaseBusinessInsightDO::getDatabaseName, databaseName);

        DatabaseBusinessInsightDO entity = getMapper().selectOne(queryWrapper);
        if (entity == null) {
            return null;
        }

        BusinessInsightDTO dto = new BusinessInsightDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    public BusinessInsightDTO saveInsight(BusinessInsightDTO dto) {
        LambdaQueryWrapper<DatabaseBusinessInsightDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DatabaseBusinessInsightDO::getDataSourceId, dto.getDataSourceId())
                .eq(DatabaseBusinessInsightDO::getDatabaseName, dto.getDatabaseName());

        DatabaseBusinessInsightDO entity = getMapper().selectOne(queryWrapper);
        if (entity == null) {
            entity = new DatabaseBusinessInsightDO();
            BeanUtils.copyProperties(dto, entity);
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            getMapper().insert(entity);
        } else {
            entity.setPlayStoreLink(dto.getPlayStoreLink());
            entity.setAppStoreLink(dto.getAppStoreLink());
            entity.setWebLink(dto.getWebLink());
            entity.setInsightContent(dto.getInsightContent());
            entity.setReferenceLinks(dto.getReferenceLinks());
            entity.setUpdateTime(LocalDateTime.now());
            getMapper().updateById(entity);
        }

        dto.setId(entity.getId());
        return dto;
    }

    public BusinessInsightDTO generateInsight(BusinessInsightDTO dto) {
        // Persist the links first so the user doesn't lose what they typed
        // if the LLM call later blows up.
        BusinessInsightDTO savedDto = saveInsight(dto);

        String modelName = pickModelForGeneration();
        String prompt = buildPrompt(dto);

        log.info("Generating business insight via WebSearchService (model={}, dataSourceId={})",
                modelName, dto.getDataSourceId());

        try {
            WebSearchResponse response = webSearchService.searchWithLLM(prompt, modelName);

            String text = response != null ? response.getSynthesizedText() : null;
            if (text == null || text.isBlank()) {
                throw new RuntimeException(
                        "LLM returned empty content. Check that the configured API key for '"
                                + modelName + "' has web search access enabled.");
            }
            savedDto.setInsightContent(text);

            List<SearchResult> sources = response.getSources();
            if (sources != null && !sources.isEmpty()) {
                // Persist sources as JSON so the UI can render citations
                // without us having to invent a new column / migration.
                savedDto.setReferenceLinks(JSON.toJSONString(sources));
            }

            saveInsight(savedDto);
            return savedDto;
        } catch (RuntimeException e) {
            log.error("Failed to generate business insight (model={})", modelName, e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate business insight (model={})", modelName, e);
            throw new RuntimeException("Failed to generate business insight: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(BusinessInsightDTO dto) {
        StringBuilder p = new StringBuilder();
        p.append("You are a business analyst. Use web search to research the following service ")
                .append("and produce a structured business analysis.\n\n");

        p.append("Service links:\n");
        if (StringUtils.hasText(dto.getPlayStoreLink())) {
            p.append("- Google Play Store: ").append(dto.getPlayStoreLink()).append("\n");
        }
        if (StringUtils.hasText(dto.getAppStoreLink())) {
            p.append("- Apple App Store: ").append(dto.getAppStoreLink()).append("\n");
        }
        if (StringUtils.hasText(dto.getWebLink())) {
            p.append("- Website: ").append(dto.getWebLink()).append("\n");
        }

        p.append("\nOutput ONLY the numbered list below. No introduction, no greeting, ")
                .append("no 'Here's...' or 'Based on...'. Start directly with '1. Service Type:'.\n\n");
        p.append("1. Service Type: Be specific about category and subcategory.\n");
        p.append("   - If game: what genre? (RPG, FPS, puzzle, simulation, UGC/metaverse, casual, etc.)\n");
        p.append("   - If e-commerce: what products? (fashion, electronics, food delivery, groceries, marketplace, etc.)\n");
        p.append("   - If SaaS: what domain? (HR, CRM, project management, analytics, etc.)\n");
        p.append("   - If content: what type? (streaming, news, education, social media, etc.)\n\n");
        p.append("2. Platform: mobile (iOS/Android), web, or both? Desktop app?\n\n");
        p.append("3. Revenue Model: How does this service make money?\n");
        p.append("   - In-app purchase, subscription, ads, transaction fee, freemium, creator economy, etc.\n\n");
        p.append("4. Key Business Characteristics: Any unique aspects of this service that would affect data analysis.\n");
        p.append("   - e.g., user-generated content, two-sided marketplace, social features, etc.\n");
        return p.toString();
    }

    /**
     * Choose which provider/model to drive the web-searching insight call.
     *
     * <p>Selection rules:
     * <ol>
     *     <li>Pick the first provider that actually has an API key configured.
     *         Priority follows {@code DeepResearchController#getPreferredModel}:
     *         Gemini &gt; Claude &gt; OpenAI. Gemini is preferred because its
     *         {@code google_search} grounding is the most mature of the three,
     *         but any of the three works.</li>
     *     <li>Within the chosen provider, honor the user's pinned chat model
     *         ({@code chatgpt.model} / {@code claude.model} /
     *         {@code gemini.model}) so insight generation uses the same model
     *         family the user already trusts for chat.</li>
     *     <li>If nothing is configured, throw a clear error pointing at the
     *         settings page rather than the misleading "Gemini API key not
     *         configured" message the old implementation always produced.</li>
     * </ol>
     *
     * <p>The returned model name is interpreted by
     * {@link WebSearchService#searchWithLLM(String, String)}, which routes on
     * substrings {@code "gemini"} / {@code "claude"} / default→OpenAI.
     */
    private String pickModelForGeneration() {
        if (hasKey(LangChainModelProvider.GEMINI_KEY)) {
            String userModel = getConfigValue(GEMINI_MODEL_KEY);
            return StringUtils.hasText(userModel) ? userModel : ModelMapper.getDefaultPrimaryModel();
        }
        if (hasKey(LangChainModelProvider.CLAUDE_KEY)) {
            String userModel = getConfigValue(CLAUDE_MODEL_KEY);
            return StringUtils.hasText(userModel) ? userModel : DEFAULT_CLAUDE_MODEL;
        }
        if (hasKey(LangChainModelProvider.OPENAI_KEY)) {
            String userModel = getConfigValue(OPENAI_MODEL_KEY);
            return StringUtils.hasText(userModel) ? userModel : DEFAULT_OPENAI_MODEL;
        }
        throw new RuntimeException(
                "No LLM API key configured. Please set an OpenAI, Claude, or Gemini API key in Settings > AI Chat.");
    }

    private boolean hasKey(String configKey) {
        return StringUtils.hasText(getConfigValue(configKey));
    }

    private String getConfigValue(String key) {
        try {
            Config config = configService.find(key).getData();
            return config != null ? config.getContent() : null;
        } catch (Exception e) {
            log.debug("Config not found for key {}: {}", key, e.getMessage());
            return null;
        }
    }
}
