package ai.inquery.server.domain.core.attachment;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-model capability matrix for chat attachments.
 *
 * <p>Verified against provider docs as of 2026-05:
 * <ul>
 *     <li>OpenAI Responses API web_search / vision / input_file — gpt-5*
 *         family + gpt-4o all support image + PDF, with the exception
 *         of gpt-5.4-nano which has restricted vision and no reliable
 *         PDF input.</li>
 *     <li>Anthropic Claude opus/sonnet/haiku — all active models support
 *         image + PDF via the {@code document} content block.</li>
 *     <li>Google Gemini 3-flash-preview / 3.1-flash-lite — image, PDF,
 *         audio and video via {@code inlineData}.</li>
 * </ul>
 *
 * <p>The matrix is intentionally allow-list-only — unknown models
 * resolve to "no multimodal support", which triggers the automatic
 * model switch path in {@code InqueryRootAgentRunner} when the user
 * attaches an unsupported file.
 *
 * <p>Provider inference is by substring match on the model id (same
 * convention {@link ai.inquery.server.domain.core.langchain.LangChainModelProvider}
 * uses everywhere else).
 */
public final class ModelCapabilities {

    public enum Capability {
        IMAGE, PDF, AUDIO, VIDEO
    }

    public enum Provider {
        OPENAI, CLAUDE, GEMINI, AZURE, UNKNOWN
    }

    /**
     * Capability matrix. Iteration order = fallback preference within a
     * provider: when the requested model can't handle the attachment,
     * we pick the first model in this map that (a) belongs to the same
     * provider and (b) supports the required capability.
     *
     * <p>OpenAI ordering puts gpt-5.5 first (highest quality) so the
     * silent auto-switch lands on the strongest still-affordable model.
     * Claude and Gemini follow the same "best within provider" rule.
     */
    private static final Map<String, EnumSet<Capability>> MATRIX;
    static {
        MATRIX = new LinkedHashMap<>();
        // OpenAI
        MATRIX.put("gpt-5.5",       EnumSet.of(Capability.IMAGE, Capability.PDF));
        MATRIX.put("gpt-5.5-pro",   EnumSet.of(Capability.IMAGE, Capability.PDF));
        MATRIX.put("gpt-5.4",       EnumSet.of(Capability.IMAGE, Capability.PDF));
        MATRIX.put("gpt-5.4-mini",  EnumSet.of(Capability.IMAGE, Capability.PDF));
        MATRIX.put("gpt-5.4-nano",  EnumSet.of(Capability.IMAGE)); // PDF unreliable on nano
        MATRIX.put("gpt-4o",        EnumSet.of(Capability.IMAGE, Capability.PDF));
        // Claude
        MATRIX.put("claude-opus-4-7",    EnumSet.of(Capability.IMAGE, Capability.PDF));
        MATRIX.put("claude-sonnet-4-6",  EnumSet.of(Capability.IMAGE, Capability.PDF));
        MATRIX.put("claude-haiku-4-5",   EnumSet.of(Capability.IMAGE, Capability.PDF));
        // Gemini
        MATRIX.put("gemini-3.5-flash", EnumSet.of(
                Capability.IMAGE, Capability.PDF, Capability.AUDIO, Capability.VIDEO));
        MATRIX.put("gemini-3-flash-preview", EnumSet.of(
                Capability.IMAGE, Capability.PDF, Capability.AUDIO, Capability.VIDEO));
        MATRIX.put("gemini-3.1-flash-lite",  EnumSet.of(Capability.IMAGE, Capability.PDF));
    }

    private ModelCapabilities() {}

    public static boolean supports(String modelName, Capability cap) {
        if (modelName == null || cap == null) return false;
        EnumSet<Capability> caps = MATRIX.get(modelName);
        if (caps == null) return false;
        return caps.contains(cap);
    }

    /**
     * Whether the matrix has a capability entry for this exact model id.
     * Callers can use this to distinguish "we know this model can't
     * handle X" from "we don't know this model at all" — only the
     * former should drive an automatic model switch.
     */
    public static boolean isKnown(String modelName) {
        return modelName != null && MATRIX.containsKey(modelName);
    }

    public static Provider providerOf(String modelName) {
        if (modelName == null) return Provider.UNKNOWN;
        String n = modelName.toLowerCase(Locale.ROOT);
        if (n.contains("claude")) return Provider.CLAUDE;
        if (n.contains("gemini")) return Provider.GEMINI;
        if (n.contains("azure"))  return Provider.AZURE;
        if (n.startsWith("gpt") || n.startsWith("o1") || n.startsWith("o3") || n.startsWith("o4")) {
            return Provider.OPENAI;
        }
        return Provider.UNKNOWN;
    }

    /**
     * Find the best model in the same provider as {@code modelName}
     * that supports {@code required}. Returns {@code null} when no
     * matching model exists — caller should surface that as a clear
     * error to the user rather than silently picking another provider
     * (we don't want to drain a key the user didn't choose).
     */
    public static String pickFallbackInSameProvider(String modelName, Capability required) {
        Provider provider = providerOf(modelName);
        if (provider == Provider.UNKNOWN) return null;

        for (Map.Entry<String, EnumSet<Capability>> e : MATRIX.entrySet()) {
            if (providerOf(e.getKey()) != provider) continue;
            if (e.getKey().equals(modelName)) continue;
            if (e.getValue().contains(required)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Capability map projected for the frontend so the input UI can
     * disable / warn before the user even hits send.
     */
    public static Map<String, EnumSet<Capability>> matrix() {
        return Map.copyOf(MATRIX);
    }
}
