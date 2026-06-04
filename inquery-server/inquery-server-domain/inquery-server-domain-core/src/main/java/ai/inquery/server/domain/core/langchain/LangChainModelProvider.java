package ai.inquery.server.domain.core.langchain;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider for LangChain4j chat models.
 * Supports multiple LLM providers: OpenAI, Anthropic Claude, Google Gemini, Azure OpenAI.
 */
@Slf4j
@Component
public class LangChainModelProvider {

    /**
     * OpenAI reasoning-effort presets exposed to callers that want to opt
     * into stronger reasoning for specific phases (e.g. NL-to-SQL).
     *
     * <p>Used only on OpenAI reasoning models (gpt-5*, o1*, o3*, o4*).
     * For Claude / Gemini / Azure / non-reasoning OpenAI models the value
     * is ignored — those providers don't expose a comparable lever in
     * LangChain4j 1.9, and treating the field as a no-op there keeps
     * call-sites uniform.
     *
     * <p>Default for {@link #getChatModel(String)} /
     * {@link #getStreamingChatModel(String)} is {@link #LOW} so chat-style
     * traffic stays fast (a couple of seconds rather than 30-70s on
     * gpt-5). SQL-generation paths should explicitly request
     * {@link #MEDIUM} via the overloaded variants below.
     *
     * <p>Values mirror the OpenAI {@code reasoning_effort} request param
     * as documented at {@code https://developers.openai.com/api/docs/models}
     * (as of 2026-05: none / low / medium / high / xhigh).
     * <ul>
     *   <li>{@link #NONE} — disables reasoning entirely (closest to a
     *       classic GPT-4 style chat). Use when latency dominates and
     *       the prompt is fully self-contained (e.g. trivial format
     *       conversions, classification with explicit rules).</li>
     *   <li>{@link #LOW} — default. Brief chain-of-thought; ~2-5s on
     *       gpt-5.5 for short prompts. Good for chat, planners,
     *       schema-reformulation, summaries.</li>
     *   <li>{@link #MEDIUM} — moderate deliberation. Used by NL→SQL
     *       paths where wrong predicates are expensive.</li>
     *   <li>{@link #HIGH} — extended reasoning. Reserve for hard
     *       analytical work; latency can reach 30-60s.</li>
     *   <li>{@link #XHIGH} — maximum reasoning. Only for offline / batch
     *       jobs where quality &gt;&gt; latency.</li>
     * </ul>
     */
    public enum ReasoningEffort {
        NONE("none"),
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        XHIGH("xhigh");

        private final String wireValue;
        ReasoningEffort(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
    }

    // Config keys (must match the codes used by ConfigController and the
    // per-provider client classes — OpenAIClient / ClaudeAIClient /
    // GeminiAIClient — otherwise the settings UI writes one row and this
    // provider reads a different (empty) row, silently breaking auth.
    //
    // Note: OpenAI is the only chat provider that consumes a custom host
    //   (for OpenAI-compatible gateways). The Claude and Gemini builders
    //   below intentionally do not call .baseUrl(...), so we don't keep a
    //   host config key for them — the settings UI hides those fields too.
    public static final String OPENAI_KEY = "chatgpt.apiKey";       // OpenAIClient.OPENAI_KEY
    public static final String OPENAI_HOST = "chatgpt.apiHost";     // OpenAIClient.OPENAI_HOST
    public static final String CLAUDE_KEY = "claude.apiKey";        // ClaudeAIClient.CLAUDE_API_KEY
    public static final String GEMINI_KEY = "gemini.apiKey";        // GeminiAIClient.GEMINI_API_KEY
    public static final String AZURE_KEY = "azureai.apiKey";
    public static final String AZURE_ENDPOINT = "azureai.endpoint";
    public static final String AZURE_DEPLOYMENT = "azureai.deploymentName";

    @Autowired
    private ConfigService configService;

    // Cache for models to avoid recreating them
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();

    // Last-resort fallback used only when the caller hands us a
    // null/empty modelName. gpt-5.4-mini is the current cheapest +
    // fastest reasoning-capable OpenAI model and a strict upgrade over
    // the previous "gpt-4" fallback in price, quality, and latency.
    private static final String FALLBACK_MODEL_NAME = "gpt-5.4-mini";

    /**
     * Get a ChatModel based on the model name.
     * Auto-detects the provider from the model name. Uses the default
     * reasoning effort ({@link ReasoningEffort#LOW}) — call the overload
     * below to override for SQL-generation or other quality-sensitive
     * phases.
     *
     * <p>Do NOT use this for tool-calling AiServices; use
     * {@link #getToolCallingChatModel(String)} there, since OpenAI
     * chat-completions rejects {@code reasoning_effort} when function
     * tools are bound.
     */
    public ChatModel getChatModel(String modelName) {
        return getChatModel(modelName, ReasoningEffort.LOW);
    }

    /**
     * Get a ChatModel with a specific reasoning effort. Effort only
     * applies to OpenAI reasoning models; ignored elsewhere. Models built
     * with different effort values are cached separately so a SQL-grade
     * model doesn't replace a chat-grade one mid-flight.
     *
     * <p>See {@link #getChatModel(String)} for the tool-calling caveat.
     */
    public ChatModel getChatModel(String modelName, ReasoningEffort effort) {
        final String finalModelName = (modelName == null || modelName.isEmpty()) ? FALLBACK_MODEL_NAME : modelName;
        final ReasoningEffort finalEffort = effort == null ? ReasoningEffort.LOW : effort;

        String cacheKey = finalModelName.toLowerCase() + "_" + finalEffort.wireValue();
        return modelCache.computeIfAbsent(cacheKey, key -> createChatModel(finalModelName, finalEffort, false));
    }

    /**
     * Get a ChatModel intended to be wired into a tool-calling
     * {@code AiServices} (anything that calls {@code .tools(...)} or
     * {@code .toolProvider(...)}). The returned model is built with
     * {@code reasoning_effort} omitted because OpenAI chat-completions
     * currently rejects the combination of reasoning_effort + function
     * tools for the gpt-5 reasoning family (see error: "Function tools
     * with reasoning_effort are not supported for gpt-5.4-mini in
     * /v1/chat/completions. Please use /v1/responses instead.").
     *
     * <p>Cached separately from the regular chat models — same modelName
     * can coexist as both a tool-calling instance and a chat instance.
     *
     * <p>For non-OpenAI providers (Claude / Gemini / Azure) this is
     * functionally identical to {@link #getChatModel(String)}.
     */
    public ChatModel getToolCallingChatModel(String modelName) {
        final String finalModelName = (modelName == null || modelName.isEmpty()) ? FALLBACK_MODEL_NAME : modelName;
        String cacheKey = finalModelName.toLowerCase() + "_toolcalling";
        return modelCache.computeIfAbsent(cacheKey,
                key -> createChatModel(finalModelName, ReasoningEffort.LOW, true));
    }

    /**
     * Get a StreamingChatModel based on the model name. See
     * {@link #getChatModel(String)} for effort defaults.
     */
    public StreamingChatModel getStreamingChatModel(String modelName) {
        return getStreamingChatModel(modelName, ReasoningEffort.LOW);
    }

    /**
     * Get a StreamingChatModel with a specific reasoning effort. See
     * {@link #getChatModel(String, ReasoningEffort)} for semantics.
     */
    public StreamingChatModel getStreamingChatModel(String modelName, ReasoningEffort effort) {
        final String finalModelName = (modelName == null || modelName.isEmpty()) ? FALLBACK_MODEL_NAME : modelName;
        final ReasoningEffort finalEffort = effort == null ? ReasoningEffort.LOW : effort;

        String cacheKey = finalModelName.toLowerCase() + "_streaming_" + finalEffort.wireValue();
        return streamingModelCache.computeIfAbsent(cacheKey,
                key -> createStreamingChatModel(finalModelName, finalEffort, false));
    }

    /**
     * Get a StreamingChatModel intended for tool-calling AiServices. See
     * {@link #getToolCallingChatModel(String)} for the rationale.
     */
    public StreamingChatModel getToolCallingStreamingChatModel(String modelName) {
        final String finalModelName = (modelName == null || modelName.isEmpty()) ? FALLBACK_MODEL_NAME : modelName;
        String cacheKey = finalModelName.toLowerCase() + "_streaming_toolcalling";
        return streamingModelCache.computeIfAbsent(cacheKey,
                key -> createStreamingChatModel(finalModelName, ReasoningEffort.LOW, true));
    }

    /**
     * Clear the model cache (call when API keys are updated).
     */
    public void clearCache() {
        modelCache.clear();
        streamingModelCache.clear();
        log.info("LangChain model cache cleared");
    }

    private ChatModel createChatModel(String modelName, ReasoningEffort effort, boolean forToolCalling) {
        String lowerModel = modelName.toLowerCase();

        try {
            if (lowerModel.contains("claude")) {
                return createAnthropicModel(modelName);
            } else if (lowerModel.contains("gemini")) {
                return createGeminiModel(modelName);
            } else if (lowerModel.contains("azure")) {
                return createAzureOpenAiModel(modelName);
            } else {
                // Default to OpenAI (gpt-*)
                return createOpenAiModel(modelName, effort, forToolCalling);
            }
        } catch (Exception e) {
            log.error("Failed to create ChatLanguageModel for {}: {}", modelName, e.getMessage());
            throw new RuntimeException("Failed to create chat model: " + modelName, e);
        }
    }



    private StreamingChatModel createStreamingChatModel(String modelName, ReasoningEffort effort, boolean forToolCalling) {
        String lowerModel = modelName.toLowerCase();

        try {
            if (lowerModel.contains("claude")) {
                return createAnthropicStreamingModel(modelName);
            } else if (lowerModel.contains("gemini")) {
                return createGeminiStreamingModel(modelName, forToolCalling);
            } else if (lowerModel.contains("azure")) {
                return createAzureOpenAiStreamingModel(modelName);
            } else {
                // Default to OpenAI
                return createOpenAiStreamingModel(modelName, effort, forToolCalling);
            }
        } catch (Exception e) {
            log.error("Failed to create StreamingChatLanguageModel for {}: {}", modelName, e.getMessage());
            throw new RuntimeException("Failed to create streaming chat model: " + modelName, e);
        }
    }

    // ===== OpenAI =====

    /**
     * OpenAI reasoning-style models (gpt-5*, o1*, o3*, o4*) reject the
     * legacy {@code max_tokens} request param and require
     * {@code max_completion_tokens} instead. LangChain4j 1.9 added a
     * separate {@code maxCompletionTokens(...)} builder method for this;
     * older chat models still use {@code maxTokens(...)} as before.
     *
     * Pattern matches the OpenAI naming convention for reasoning families
     * as of 2026-05; pure {@code gpt-4*} models keep the legacy path.
     */
    private static boolean isOpenAiReasoningModel(String modelName) {
        if (modelName == null) return false;
        String n = modelName.toLowerCase();
        return n.startsWith("gpt-5")
                || n.startsWith("o1")
                || n.startsWith("o3")
                || n.startsWith("o4");
    }

    // (Removed: isOpenAi55Family — the effort/tools incompat turned out
    //  not to be 5.5-specific. As of 2026-05 OpenAI rejects
    //  reasoning_effort whenever function tools are bound on
    //  /v1/chat/completions for the entire gpt-5 reasoning family
    //  ("Function tools with reasoning_effort are not supported for
    //  gpt-5.4-mini in /v1/chat/completions. Please use /v1/responses
    //  instead."). We now switch on the call-site instead: tool-calling
    //  callers use getToolCallingChatModel(...) which omits the
    //  reasoning_effort param entirely; tool-free callers (chat,
    //  planner, SQL markdown generation) keep using getChatModel(...,
    //  effort) and honor the hybrid LOW/MEDIUM policy. Migrating to
    //  LangChain4j's OpenAiResponsesStreamingChatModel would let us
    //  honor effort on tool calls too, but it's streaming-only in
    //  LangChain4j 1.9 and our agent.answer path is blocking — so
    //  deferred.)

    // OpenAI verbosity (gpt-5 family). "low" keeps replies concise so the
    // model doesn't pad short answers with filler — saves output tokens
    // and shortens the SSE stream the user sees. Verbosity is currently
    // tied to effort here (low for chat, medium reasoning still pairs
    // well with low verbosity since long reasoning ≠ long output).
    private static final String OPENAI_VERBOSITY = "low";

    /**
     * Build an OpenAI chat model. Reasoning effort is honored only for
     * reasoning-family models (gpt-5*, o1*, o3*, o4*); on classic GPT-4
     * style models the {@code effort} arg is ignored because LangChain4j's
     * {@code reasoningEffort(...)} maps to a request param OpenAI rejects
     * for non-reasoning models.
     *
     * <p>Default callers (chat, planner, schema reformulation, etc.) pass
     * {@link ReasoningEffort#LOW} via the no-arg overload — that keeps
     * GPT-5 latency in the 2-5s range for trivial prompts. SQL-generation
     * paths bump to {@link ReasoningEffort#MEDIUM} so the model spends
     * more deliberation on schema/predicate selection where mistakes are
     * expensive to recover from at execution time.
     */
    /**
     * Build an OpenAI chat model.
     *
     * @param effort         the requested reasoning effort. Honored only
     *                       when {@code forToolCalling} is {@code false}
     *                       and the model is a reasoning model — see
     *                       {@link #isOpenAiReasoningModel(String)}.
     * @param forToolCalling {@code true} if the caller will bind
     *                       function tools to the resulting model. In
     *                       that case we omit {@code reasoning_effort}
     *                       entirely because OpenAI chat-completions
     *                       rejects the combination for gpt-5 reasoning
     *                       models (the model then falls back to
     *                       OpenAI's default {@code medium} effort).
     */
    private ChatModel createOpenAiModel(String modelName, ReasoningEffort effort, boolean forToolCalling) {
        String apiKey = getConfigValue(OPENAI_KEY);
        String apiHost = getConfigValue(OPENAI_HOST);
        boolean reasoning = isOpenAiReasoningModel(modelName);
        boolean sendEffort = reasoning && !forToolCalling;
        String effortValue = effort.wireValue();

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(5))
                .maxRetries(3);
        // Prevent response truncation for complex SQL queries. Token-cap
        //   parameter name diverged starting with the reasoning family.
        if (reasoning) {
            builder.maxCompletionTokens(8192);
            if (sendEffort) {
                builder.reasoningEffort(effortValue)
                        .customParameters(java.util.Map.of("verbosity", OPENAI_VERBOSITY));
            }
        } else {
            builder.maxTokens(8192);
        }

        if (apiHost != null && !apiHost.isEmpty() && !apiHost.contains("api.openai.com")) {
            builder.baseUrl(apiHost);
        }

        log.info("Created OpenAI ChatModel: {} (reasoning={}, toolCalling={}, effort={}, verbosity={})",
                modelName,
                reasoning,
                forToolCalling,
                sendEffort ? effortValue : (reasoning ? "omitted-toolCalling" : "n/a"),
                sendEffort ? OPENAI_VERBOSITY : (reasoning ? "omitted-toolCalling" : "n/a"));
        return builder.build();
    }

    private StreamingChatModel createOpenAiStreamingModel(String modelName, ReasoningEffort effort, boolean forToolCalling) {
        String apiKey = getConfigValue(OPENAI_KEY);
        String apiHost = getConfigValue(OPENAI_HOST);
        boolean reasoning = isOpenAiReasoningModel(modelName);
        boolean sendEffort = reasoning && !forToolCalling;
        String effortValue = effort.wireValue();

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(5));
        // Increased for infographic HTML generation. See createOpenAiModel
        //   for why the parameter name depends on the model family.
        if (reasoning) {
            builder.maxCompletionTokens(16384);
            if (sendEffort) {
                builder.reasoningEffort(effortValue)
                        .customParameters(java.util.Map.of("verbosity", OPENAI_VERBOSITY));
            }
        } else {
            builder.maxTokens(16384);
        }

        if (apiHost != null && !apiHost.isEmpty() && !apiHost.contains("api.openai.com")) {
            builder.baseUrl(apiHost);
        }

        log.info("Created OpenAI StreamingChatModel: {} (reasoning={}, toolCalling={}, effort={}, verbosity={})",
                modelName,
                reasoning,
                forToolCalling,
                sendEffort ? effortValue : (reasoning ? "omitted-toolCalling" : "n/a"),
                sendEffort ? OPENAI_VERBOSITY : (reasoning ? "omitted-toolCalling" : "n/a"));
        return builder.build();
    }

    // ===== Anthropic Claude =====

    private ChatModel createAnthropicModel(String modelName) {
        String apiKey = getConfigValue(CLAUDE_KEY);

        // Prompt caching is now GA at Anthropic. The legacy
        //   `anthropic-beta: prompt-caching-2024-07-31` header is rejected
        //   ("Unexpected value(s) for the `anthropic-beta` header") and
        //   must not be sent. LangChain4j applies cache_control via
        //   cacheSystemMessages(true), no opt-in header required.
        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .cacheSystemMessages(true)          // Cache system messages for cost reduction
                // Note: cacheTools(true) not used - creates separate cache block per tool,
                // can exceed Anthropic's 4 cache_control block limit
                .logResponses(true)                 // Log responses to verify cache token usage
                .timeout(Duration.ofMinutes(5))
                .maxRetries(3)
                .maxTokens(8192)  // Prevent response truncation for complex SQL queries
                .build();

        log.info("Created Anthropic ChatModel with prompt caching: {}", modelName);
        return model;
    }

    private StreamingChatModel createAnthropicStreamingModel(String modelName) {
        String apiKey = getConfigValue(CLAUDE_KEY);

        // See createAnthropicModel() — beta header removed (now GA).
        AnthropicStreamingChatModel model = AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .cacheSystemMessages(true)          // Cache system messages for cost reduction
                // Note: cacheTools(true) not used - creates separate cache block per tool,
                // can exceed Anthropic's 4 cache_control block limit
                .logResponses(true)                 // Log responses to verify cache token usage
                .timeout(Duration.ofMinutes(5))
                .maxTokens(16384)  // Increased for infographic HTML generation
                .build();

        log.info("Created Anthropic StreamingChatModel with prompt caching: {}", modelName);
        return model;
    }

    // ===== Google Gemini =====

    private ChatModel createGeminiModel(String modelName) {
        return createGeminiModelInternal(modelName, true);
    }

    private ChatModel createGeminiModelInternal(String modelName, boolean thinking) {
        String apiKey = getConfigValue(GEMINI_KEY);

        // returnThinking(true) + sendThinking(true) are BOTH required for the
        //   multi-turn tool-calling loop. Without returnThinking the
        //   thought_signatures are never captured into AiMessage, so the
        //   next request can't echo them back and Gemini rejects with
        //   "Function call is missing a thought_signature in functionCall
        //   parts" (HTTP 400).
        //
        // Side-effect: for very short user messages Gemini sometimes writes
        //   the entire reply into its thinking trace and produces NO final
        //   text token. AiServices then returns an empty string. That case
        //   is handled by retrying through getPlainChatModel() in the
        //   runner (a thinking-disabled variant that always emits final
        //   text).
        //
        // NO thinkingConfig — explicit thinkingBudget corrupts tool call arguments.
        GoogleAiGeminiChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(5))
                .maxRetries(3)
                .maxOutputTokens(8192)
                .returnThinking(thinking)
                .sendThinking(thinking)
                .build();

        log.info("Created Gemini ChatModel: {} (returnThinking={}, sendThinking={})", modelName, thinking, thinking);
        return model;
    }

    /**
     * A "plain chat" variant of the chat model with model-side thinking
     * disabled where applicable. Use this for one-shot non-tool LLM calls
     * (e.g. the runner's fallback when the main tool-calling model returned
     * an empty body). Other providers fall back to the regular chat model.
     */
    public ChatModel getPlainChatModel(String modelName) {
        final String finalModelName = (modelName == null || modelName.isEmpty()) ? FALLBACK_MODEL_NAME : modelName;
        String cacheKey = finalModelName.toLowerCase() + "_plain";
        return modelCache.computeIfAbsent(cacheKey, key -> createPlainChatModel(finalModelName));
    }

    private ChatModel createPlainChatModel(String modelName) {
        String lowerModel = modelName.toLowerCase();
        try {
            if (lowerModel.contains("gemini")) {
                return createGeminiModelInternal(modelName, false);
            }
            // For non-Gemini providers thinking does not interfere with
            // AiMessage.text(), so the regular (LOW-effort) model is fine
            // — plain chat is short by definition. Tool-free path: send
            // reasoning_effort.
            return createChatModel(modelName, ReasoningEffort.LOW, false);
        } catch (Exception e) {
            log.error("Failed to create plain ChatModel for {}: {}", modelName, e.getMessage());
            return createChatModel(modelName, ReasoningEffort.LOW, false);
        }
    }

    // Long-form output cap. The default plain/chat models cap output at
    // ~8192 tokens which is fine for chat replies and SQL fragments but
    // too small for synthesis tasks that must emit a comprehensive JSON
    // report (multi-section narrative + embedded tables + citations).
    // 8192 tokens routinely truncates the JSON mid-output, which is what
    // breaks the Deep Research report contract. 32k is well within the
    // provider limits (Gemini 2.5/3 = 65k, GPT-5 family ≫ 32k, Claude w/
    // output-128k beta) and leaves enough headroom for prompts that ask
    // for "comprehensive" reports.
    private static final int LONG_FORM_MAX_OUTPUT_TOKENS = 32768;

    /**
     * Long-form variant of the plain chat model. Use this for one-shot
     * synthesis calls that must emit a large structured payload (e.g. the
     * Deep Research final report JSON). The underlying provider's output
     * token limit is raised so the response cannot be truncated mid-JSON.
     */
    public ChatModel getLongFormChatModel(String modelName) {
        final String finalModelName = (modelName == null || modelName.isEmpty()) ? FALLBACK_MODEL_NAME : modelName;
        String cacheKey = finalModelName.toLowerCase() + "_longform";
        return modelCache.computeIfAbsent(cacheKey, key -> createLongFormChatModel(finalModelName));
    }

    private ChatModel createLongFormChatModel(String modelName) {
        String lowerModel = modelName.toLowerCase();
        try {
            if (lowerModel.contains("gemini")) {
                String apiKey = getConfigValue(GEMINI_KEY);
                return GoogleAiGeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .timeout(Duration.ofMinutes(5))
                        .maxRetries(3)
                        .maxOutputTokens(LONG_FORM_MAX_OUTPUT_TOKENS)
                        .returnThinking(false)
                        .sendThinking(false)
                        .build();
            }
            if (lowerModel.contains("claude")) {
                String apiKey = getConfigValue(CLAUDE_KEY);
                return AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .cacheSystemMessages(true)
                        .logResponses(true)
                        .timeout(Duration.ofMinutes(5))
                        .maxRetries(3)
                        .maxTokens(LONG_FORM_MAX_OUTPUT_TOKENS)
                        .build();
            }
            if (lowerModel.contains("azure")) {
                String apiKey = getConfigValue(AZURE_KEY);
                String endpoint = getConfigValue(AZURE_ENDPOINT);
                String deploymentName = getConfigValue(AZURE_DEPLOYMENT);
                if (deploymentName == null || deploymentName.isEmpty()) {
                    deploymentName = modelName.replace("azure-", "");
                }
                return AzureOpenAiChatModel.builder()
                        .apiKey(apiKey)
                        .endpoint(endpoint)
                        .deploymentName(deploymentName)
                        .timeout(Duration.ofMinutes(5))
                        .maxRetries(3)
                        .maxTokens(LONG_FORM_MAX_OUTPUT_TOKENS)
                        .build();
            }
            String apiKey = getConfigValue(OPENAI_KEY);
            String apiHost = getConfigValue(OPENAI_HOST);
            boolean reasoning = isOpenAiReasoningModel(modelName);
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .timeout(Duration.ofMinutes(5))
                    .maxRetries(3);
            if (reasoning) {
                builder.maxCompletionTokens(LONG_FORM_MAX_OUTPUT_TOKENS);
            } else {
                builder.maxTokens(LONG_FORM_MAX_OUTPUT_TOKENS);
            }
            if (apiHost != null && !apiHost.isEmpty() && !apiHost.contains("api.openai.com")) {
                builder.baseUrl(apiHost);
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to create long-form ChatModel for {}: {}", modelName, e.getMessage());
            return getPlainChatModel(modelName);
        }
    }

    private StreamingChatModel createGeminiStreamingModel(String modelName, boolean forToolCalling) {
        String apiKey = getConfigValue(GEMINI_KEY);

        GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder builder = GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(5))
                .maxOutputTokens(16384)
                .returnThinking(forToolCalling);

        if (!forToolCalling) {
            // Non-tool streaming (SQL markdown generation) needs a fast final
            // answer, not Gemini's thought trace. Gemini 3.5 defaults to
            // medium thinking, which can noticeably delay the first text token.
            builder.thinkingConfig(GeminiThinkingConfig.builder()
                    .includeThoughts(false)
                    .thinkingLevel(GeminiThinkingConfig.GeminiThinkingLevel.LOW)
                    .build());
        }

        GoogleAiGeminiStreamingChatModel model = builder.build();

        log.info("Created Gemini StreamingChatModel: {} (returnThinking={}, thinkingLevel={})",
                modelName, forToolCalling, forToolCalling ? "default" : "LOW");
        return model;
    }

    // ===== Azure OpenAI =====

    private ChatModel createAzureOpenAiModel(String modelName) {
        String apiKey = getConfigValue(AZURE_KEY);
        String endpoint = getConfigValue(AZURE_ENDPOINT);
        String deploymentName = getConfigValue(AZURE_DEPLOYMENT);

        // Extract deployment name from model if not configured
        if (deploymentName == null || deploymentName.isEmpty()) {
            deploymentName = modelName.replace("azure-", "");
        }

        AzureOpenAiChatModel model = AzureOpenAiChatModel.builder()
                .apiKey(apiKey)
                .endpoint(endpoint)
                .deploymentName(deploymentName)
                .timeout(Duration.ofMinutes(5))
                .maxRetries(3)
                .maxTokens(8192)  // Prevent response truncation for complex SQL queries
                .build();

        log.info("Created Azure OpenAI ChatModel: {}", deploymentName);
        return model;
    }

    private StreamingChatModel createAzureOpenAiStreamingModel(String modelName) {
        String apiKey = getConfigValue(AZURE_KEY);
        String endpoint = getConfigValue(AZURE_ENDPOINT);
        String deploymentName = getConfigValue(AZURE_DEPLOYMENT);

        if (deploymentName == null || deploymentName.isEmpty()) {
            deploymentName = modelName.replace("azure-", "");
        }

        AzureOpenAiStreamingChatModel model = AzureOpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .endpoint(endpoint)
                .deploymentName(deploymentName)
                .timeout(Duration.ofMinutes(5))
                .maxTokens(16384)  // Increased for infographic HTML generation
                .build();

        log.info("Created Azure OpenAI StreamingChatModel: {}", deploymentName);
        return model;
    }

    private String getConfigValue(String key) {
        try {
            Config config = configService.find(key).getData();
            return config != null ? config.getContent() : null;
        } catch (Exception e) {
            log.warn("Failed to get config {}: {}", key, e.getMessage());
            return null;
        }
    }
}
