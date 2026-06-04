package ai.inquery.server.domain.api.service;

import java.util.function.Consumer;

/**
 * Service for interacting with AI models.
 */
public interface AIService {

    /**
     * Generates text/code based on the prompt and model.
     * @param prompt The prompt to send to the AI.
     * @param model The model identifier (e.g., "gpt-5.4-mini", "gemini-3.5-flash").
     * @return The generated response.
     */
    String generate(String prompt, String model);

    /**
     * Generates text/code with streaming support.
     * Each token/chunk is sent to the tokenConsumer as it's generated.
     * @param prompt The prompt to send to the AI.
     * @param model The model identifier.
     * @param tokenConsumer Callback that receives each token/chunk as it's generated.
     * @return The full generated response.
     */
    String generateWithStreaming(String prompt, String model, Consumer<String> tokenConsumer);
}
