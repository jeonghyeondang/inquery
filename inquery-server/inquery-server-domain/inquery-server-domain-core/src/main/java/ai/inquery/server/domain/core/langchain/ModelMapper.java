package ai.inquery.server.domain.core.langchain;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Maps primary models to their fast counterparts and provides prompt optimization.
 *
 * Primary models are used for:
 * - SQL generation (SqlWriterAgent)
 * - Result analysis (ResultAnalyzerAgent)
 *
 * Fast models are used for:
 * - Query clarification (ClarificationAgent)
 * - Query classification
 *
 * Prompt repetition (x2) is applied to fast models only, based on:
 * "Prompt Repetition Improves Non-Reasoning LLMs" (Google Research, 2025)
 * - arxiv.org/abs/2512.14982
 * - 47 wins / 0 losses across 70 model-benchmark pairs
 * - Effective for non-reasoning (fast) models; neutral for reasoning models
 * - Input tokens x2, output tokens unchanged, latency unchanged
 */
public class ModelMapper {

    // Current-generation roster as of 2026-05. GPT-5.5 itself does NOT
    // ship a -mini/-nano variant — gpt-5.4-mini is the strongest current
    // mini and is the canonical fast counterpart for both gpt-5.5 and
    // gpt-5.5-pro. Claude haiku-4-5 covers everything below opus/sonnet.
    private static final Map<String, String> PRIMARY_TO_FAST = Map.ofEntries(
        // Gemini
        Map.entry("gemini-3.5-flash", "gemini-3.1-flash-lite"),
        Map.entry("gemini-3-flash-preview", "gemini-3.1-flash-lite"),

        // OpenAI
        Map.entry("gpt-5.5", "gpt-5.4-mini"),
        Map.entry("gpt-5.5-pro", "gpt-5.4-mini"),

        // Claude
        Map.entry("claude-opus-4-7", "claude-haiku-4-5"),
        Map.entry("claude-sonnet-4-6", "claude-haiku-4-5")
    );

    // All known fast (non-reasoning, lightweight) models. Used to gate
    // prompt-repetition and other small-model-only optimizations.
    private static final Set<String> FAST_MODELS = Set.of(
        // Gemini
        "gemini-3.1-flash-lite",
        // OpenAI
        "gpt-5.4-mini",
        "gpt-5.4-nano",
        // Claude
        "claude-haiku-4-5"
    );

    // Default fast model if no mapping found
    private static final String DEFAULT_FAST_MODEL = "gemini-3.1-flash-lite";

    /**
     * Get the fast model for a given primary model.
     * Falls back to the primary model itself if no mapping exists.
     */
    public static String getFastModel(String primaryModel) {
        if (primaryModel == null || primaryModel.isEmpty()) {
            return DEFAULT_FAST_MODEL;
        }
        return PRIMARY_TO_FAST.getOrDefault(primaryModel, primaryModel);
    }

    /**
     * Check if a model is a fast (non-reasoning, lightweight) model.
     * Used to determine whether prompt repetition should be applied.
     */
    public static boolean isFastModel(String model) {
        return model != null && FAST_MODELS.contains(model);
    }

    /**
     * Check if a model is a primary (high-quality) model.
     */
    public static boolean isPrimaryModel(String model) {
        return PRIMARY_TO_FAST.containsKey(model);
    }

    /**
     * Get the default primary model.
     */
    public static String getDefaultPrimaryModel() {
        return "gemini-3.5-flash";
    }

    /**
     * Get the default fast model.
     */
    public static String getDefaultFastModel() {
        return DEFAULT_FAST_MODEL;
    }

    /**
     * Apply prompt repetition for fast models (x2, end-position).
     *
     * Based on "Prompt Repetition Improves Non-Reasoning LLMs" (arxiv 2512.14982):
     * - Repeats the prompt once at the end (x2 total)
     * - Only applied to fast/non-reasoning models (no effect on reasoning models)
     * - 47 wins / 0 losses across 70 model-benchmark pairs
     * - Gemini Flash-Lite NameIndex: 21.3% -> 97.3%
     * - Input tokens x2, output tokens unchanged, latency unchanged
     *
     * @param prompt The original prompt
     * @param model The model that will process this prompt
     * @return The prompt repeated x2 if model is a fast model, otherwise unchanged
     */
    public static String optimizePrompt(String prompt, String model) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }
        if (!isFastModel(model)) {
            return prompt;
        }
        return prompt + "\n\n" + prompt;
    }

    /**
     * Create a chatRequestTransformer for AiServices that applies prompt repetition.
     *
     * Repeats BOTH SystemMessage and UserMessage content (x2) for fast models.
     * The paper (arxiv 2512.14982) repeats the entire &lt;QUERY&gt;, which includes
     * instructions + input. System instructions contain critical directions that
     * benefit most from repetition due to causal attention reinforcement.
     *
     * @param model The model name - repetition only applied if it's a fast model
     * @return A UnaryOperator that repeats message content for fast models, identity otherwise
     */
    public static UnaryOperator<ChatRequest> promptRepetitionTransformer(String model) {
        if (!isFastModel(model)) {
            return UnaryOperator.identity();
        }
        return chatRequest -> {
            List<ChatMessage> messages = new ArrayList<>(chatRequest.messages());
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof SystemMessage) {
                    SystemMessage sysMsg = (SystemMessage) messages.get(i);
                    String original = sysMsg.text();
                    messages.set(i, SystemMessage.from(original + "\n\n" + original));
                } else if (messages.get(i) instanceof UserMessage) {
                    UserMessage userMsg = (UserMessage) messages.get(i);
                    String original = userMsg.singleText();
                    messages.set(i, UserMessage.from(original + "\n\n" + original));
                }
            }
            return ChatRequest.builder()
                    .messages(messages)
                    .parameters(chatRequest.parameters())
                    .build();
        };
    }
}
