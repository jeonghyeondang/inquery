package ai.inquery.server.domain.core.langchain.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps an existing ToolProvider to add user approval before tool execution.
 * Read-only tools execute directly; write tools require user confirmation via SSE.
 */
@Slf4j
public class ApprovalToolProvider implements ToolProvider {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Tool name patterns that are considered read-only (no approval needed).
     * Matched case-insensitively against the tool name.
     */
    private static final List<String> READ_ONLY_PATTERNS = List.of(
            "search", "get", "read", "list", "find", "fetch", "query", "view", "describe"
    );

    /** Technical parameters hidden from the user in approval UI. */
    private static final Set<String> HIDDEN_PARAMS = Set.of(
            "jq", "path", "method", "version", "status", "id"
    );

    private static final Pattern TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("\"channel\"\\s*:\\s*\"([^\"]+)\"");

    private final ToolProvider delegate;
    private final ToolApprovalCallback approvalCallback;
    /** Service base URLs for generating links (e.g., "confluence" → "https://xxx.atlassian.net") */
    private final Map<String, String> serviceBaseUrls;
    /** Confluence credentials for resolving Space Key → Space ID */
    private final Map<String, String> confluenceCredentials;
    /** Jira credentials for user search in approval UI */
    private final Map<String, String> jiraCredentials;
    /** Last resolved Space Key from user's URL input (used to build page URLs in results) */
    private volatile String lastResolvedSpaceKey;

    public ApprovalToolProvider(ToolProvider delegate,
                                ToolApprovalCallback approvalCallback) {
        this(delegate, approvalCallback, Map.of(), Map.of(), Map.of());
    }

    public ApprovalToolProvider(ToolProvider delegate,
                                ToolApprovalCallback approvalCallback,
                                Map<String, String> serviceBaseUrls) {
        this(delegate, approvalCallback, serviceBaseUrls, Map.of(), Map.of());
    }

    public ApprovalToolProvider(ToolProvider delegate,
                                ToolApprovalCallback approvalCallback,
                                Map<String, String> serviceBaseUrls,
                                Map<String, String> confluenceCredentials) {
        this(delegate, approvalCallback, serviceBaseUrls, confluenceCredentials, Map.of());
    }

    public ApprovalToolProvider(ToolProvider delegate,
                                ToolApprovalCallback approvalCallback,
                                Map<String, String> serviceBaseUrls,
                                Map<String, String> confluenceCredentials,
                                Map<String, String> jiraCredentials) {
        this.delegate = delegate;
        this.approvalCallback = approvalCallback;
        this.serviceBaseUrls = serviceBaseUrls != null ? serviceBaseUrls : Map.of();
        this.confluenceCredentials = confluenceCredentials != null ? confluenceCredentials : Map.of();
        this.jiraCredentials = jiraCredentials != null ? jiraCredentials : Map.of();
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult original = delegate.provideTools(request);
        if (original == null || original.tools() == null || original.tools().isEmpty()) {
            return original;
        }

        Map<ToolSpecification, ToolExecutor> wrappedTools = new LinkedHashMap<>();

        for (Map.Entry<ToolSpecification, ToolExecutor> entry : original.tools().entrySet()) {
            ToolSpecification spec = entry.getKey();
            ToolExecutor executor = entry.getValue();

            if (isReadOnly(spec.name())) {
                wrappedTools.put(spec, wrapWithLogging(spec, executor));
                log.debug("Tool '{}' classified as read-only, no approval needed", spec.name());
            } else {
                wrappedTools.put(spec, wrapWithApproval(spec, executor));
                log.debug("Tool '{}' classified as write operation, approval required", spec.name());
            }
        }

        return new ToolProviderResult(wrappedTools);
    }

    private boolean isReadOnly(String toolName) {
        if (toolName == null) return false;
        String lower = toolName.toLowerCase();
        return READ_ONLY_PATTERNS.stream().anyMatch(lower::contains);
    }

    private ToolExecutor wrapWithLogging(ToolSpecification spec, ToolExecutor original) {
        return (request, memoryId) -> {
            log.info("[MCP ReadOnly] Tool: '{}', Args: {}", spec.name(), request.arguments());
            String result = original.execute(request, memoryId);
            String truncated = result != null && result.length() > 500 ? result.substring(0, 500) + "...(truncated)" : result;
            log.info("[MCP ReadOnly] Tool: '{}', Response: {}", spec.name(), truncated);
            return result;
        };
    }

    private ToolExecutor wrapWithApproval(ToolSpecification spec, ToolExecutor original) {
        return (request, memoryId) -> {
            log.info("Tool '{}' requires approval, sending request to user", spec.name());

            String rawArgs = request.arguments();
            log.info("[ToolApproval] Tool name: '{}', Raw arguments: {}", spec.name(), rawArgs);

            Map<String, Object> argMap = parseArgs(rawArgs);
            log.info("[ToolApproval] Parsed argMap keys: {}, argMap values types: {}",
                    argMap.keySet(),
                    argMap.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue() != null ? e.getValue().getClass().getSimpleName() : "null")));

            // Build human-readable description, target, and editable parameters
            String description = buildDescription(spec.name(), argMap, rawArgs);
            String target = extractTarget(spec.name(), argMap, rawArgs);
            List<ToolApprovalRequest.ToolParameter> params = buildUserFacingParameters(spec.name(), argMap, rawArgs);

            log.info("[ToolApproval] description='{}', target='{}', params count={}, param names={}",
                    description, target, params.size(),
                    params.stream().map(ToolApprovalRequest.ToolParameter::getName).toList());

            ToolApprovalRequest approvalRequest = ToolApprovalRequest.builder()
                    .toolName(spec.name())
                    .toolDisplayName(humanize(spec.name()))
                    .description(description)
                    .target(target)
                    .parameters(params)
                    .build();

            try {
                ToolApprovalResponse response = approvalCallback.requestApproval(approvalRequest);

                if (response.getParameters() != null && !response.getParameters().isEmpty()) {
                    log.info("[ToolApproval] Applying {} modified parameters for '{}'",
                            response.getParameters().size(), spec.name());
                    request = applyModifiedParameters(request, response.getParameters());
                }

                log.info("[ToolApproval] Tool '{}' approved, executing with args: {}",
                        spec.name(), request.arguments() != null ?
                            (request.arguments().length() > 500 ? request.arguments().substring(0, 500) + "..." : request.arguments()) : "null");

                String result = original.execute(request, memoryId);

                log.info("[ToolApproval] Tool '{}' execution result ({}): {}",
                        spec.name(),
                        result != null ? result.length() + " chars" : "null",
                        result != null ? (result.length() > 500 ? result.substring(0, 500) + "..." : result) : "null");

                // Check if the tool execution failed and notify frontend
                boolean toolFailed = result != null && (result.contains("\"ok\":false") || result.contains("\"error\""));
                if (toolFailed) {
                    String errorMsg = extractErrorMessage(result);
                    approvalCallback.notifyToolResult(approvalRequest.getRequestId(), false, errorMsg);
                } else {
                    approvalCallback.notifyToolResult(approvalRequest.getRequestId(), true, null);
                }

                // Enrich Confluence create results with page URL so AI can include it in response
                result = enrichResultWithUrl(spec.name(), request.arguments(), result);

                return result;

            } catch (ToolApprovalManager.ToolApprovalException e) {
                log.info("Tool '{}' denied or timed out: {}", spec.name(), e.getMessage());
                return "Tool execution was denied by the user: " + e.getMessage();
            }
        };
    }

    /**
     * Extract target destination (e.g., Confluence space, Slack channel, Jira project, GitHub repo).
     */
    private String extractTarget(String toolName, Map<String, Object> args, String rawArgs) {
        // Also search inside "body" map (MCP tools often nest params under body)
        Map<String, Object> bodyMap = getBodyAsMap(args);

        // Slack: channel or channel_id (top-level or inside body)
        String channel = findInArgsOrBody(args, bodyMap, "channel", "channel_id");
        if (channel != null) return "#" + channel;

        // Confluence: clickable link to instance (label = hostname, not "Confluence" to avoid repetition with badge)
        String spaceKey = findInArgsOrBody(args, bodyMap, "spaceKey", "space_key");
        String spaceId = findInArgsOrBody(args, bodyMap, "spaceId");
        if (spaceKey != null || spaceId != null) {
            String confUrl = serviceBaseUrls.get("confluence");
            if (confUrl != null && !confUrl.isBlank()) {
                String host = extractHost(confUrl);
                return host + " | " + confUrl + "/wiki";
            }
        }

        // Jira: projectKey or project
        String project = findInArgsOrBody(args, bodyMap, "projectKey", "project_key", "project");
        if (project != null) {
            String jiraUrl = serviceBaseUrls.get("jira");
            if (jiraUrl != null && !jiraUrl.isBlank()) {
                return project + " | " + jiraUrl + "/browse/" + project;
            }
            return project;
        }

        // GitHub: repo or repository
        String repo = findInArgsOrBody(args, bodyMap, "repo", "repository", "owner");
        if (repo != null) {
            String ghUrl = serviceBaseUrls.get("github");
            if (ghUrl != null && !ghUrl.isBlank()) {
                return repo + " | " + ghUrl + "/" + repo;
            }
            return repo;
        }

        return null;
    }

    /** Get the "body" argument as a Map if it is one, or null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getBodyAsMap(Map<String, Object> args) {
        Object body = args.get("body");
        if (body instanceof Map) {
            return (Map<String, Object>) body;
        }
        return null;
    }

    /** Search for a value by multiple key names, first in top-level args, then inside body. */
    private String findInArgsOrBody(Map<String, Object> args, Map<String, Object> bodyMap, String... keys) {
        for (String key : keys) {
            String val = getArgString(args, key);
            if (val != null) return val;
        }
        if (bodyMap != null) {
            for (String key : keys) {
                String val = getArgString(bodyMap, key);
                if (val != null) return val;
            }
        }
        return null;
    }

    private String getArgString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    /**
     * Build a human-readable description of what the tool will do.
     */
    private String buildDescription(String toolName, Map<String, Object> args, String rawArgs) {
        String lower = toolName.toLowerCase();
        String action = detectAction(lower);

        // Extract key info from arguments
        String title = extractFromArgs(args, rawArgs, "title");
        String channel = extractFromArgs(args, rawArgs, "channel");

        StringBuilder desc = new StringBuilder();
        desc.append(action);

        if (title != null) {
            desc.append(" \"").append(title).append("\"");
        }
        if (channel != null) {
            desc.append(" in #").append(channel);
        }
        // Service name is NOT appended here — it's already shown via the service badge and icon

        return desc.toString();
    }

    /**
     * Build parameters that the user can understand and edit.
     * Hides technical params (jq, path, version, etc.) and extracts readable content.
     *
     * MCP tools may send args in two formats:
     * 1) Flat: {"spaceId": "...", "title": "...", "body": {...}} — top-level fields
     * 2) HTTP-wrapped: {"method": "POST", "path": "...", "body": "{\"spaceId\":...}"} — everything in body
     *
     * For known services, we apply service-specific UI regardless of format.
     */
    private List<ToolApprovalRequest.ToolParameter> buildUserFacingParameters(
            String toolName, Map<String, Object> argMap, String rawArgs) {
        List<ToolApprovalRequest.ToolParameter> params = new ArrayList<>();
        String service = detectService(toolName.toLowerCase());

        log.info("[ToolApproval] buildUserFacingParameters: service='{}', argMap keys={}, rawArgs length={}",
                service, argMap.keySet(), rawArgs != null ? rawArgs.length() : 0);

        if (argMap.isEmpty()) {
            if (rawArgs != null && !rawArgs.isBlank()) {
                params.add(ToolApprovalRequest.ToolParameter.builder()
                        .name("arguments")
                        .displayName("Arguments")
                        .type("textarea")
                        .value(rawArgs)
                        .required(true)
                        .build());
            }
            return params;
        }

        // For known services, try service-specific parameter extraction on the full argMap.
        // This handles both flat args (top-level spaceId/title/body) and HTTP-wrapped args (body wrapper).
        if (service != null) {
            Map<String, Object> effectiveMap = resolveEffectiveArgs(argMap);
            log.info("[ToolApproval] effectiveMap keys for service '{}': {}", service, effectiveMap.keySet());

            switch (service) {
                case "Confluence":
                    addConfluenceParams(effectiveMap, params);
                    break;
                case "Slack":
                    addSlackBodyParams(effectiveMap, params);
                    break;
                case "Jira":
                    addJiraBodyParams(effectiveMap, params);
                    break;
                case "GitHub":
                    addGitHubBodyParams(effectiveMap, params);
                    break;
                default:
                    addGenericBodyParams(effectiveMap, params);
                    break;
            }

            if (!params.isEmpty()) {
                log.info("[ToolApproval] Service-specific params: {}", params.stream()
                        .map(p -> p.getName() + "(" + p.getType() + ")").toList());
                return params;
            }
            log.info("[ToolApproval] Service-specific extraction produced 0 params, falling back to generic");
        }

        // Generic fallback: show non-hidden params
        for (Map.Entry<String, Object> entry : argMap.entrySet()) {
            String key = entry.getKey();
            Object rawValue = entry.getValue();

            if (HIDDEN_PARAMS.contains(key)) {
                continue;
            }

            // If body is a JSON object, expand its fields
            if ("body".equalsIgnoreCase(key) && rawValue != null) {
                String bodyJson = toJsonString(rawValue);
                if (bodyJson.startsWith("{")) {
                    addGenericBodyParams(parseJsonMap(bodyJson), params);
                    continue;
                }
            }

            String value = rawValue != null ? rawValue.toString() : "";
            String type = value.length() > 100 ? "textarea" : "text";
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name(key)
                    .displayName(humanize(key))
                    .type(type)
                    .value(value)
                    .required(true)
                    .build());
        }

        // Ultimate fallback: if still empty, show raw args
        if (params.isEmpty() && rawArgs != null && !rawArgs.isBlank()) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("arguments")
                    .displayName("Arguments")
                    .type("textarea")
                    .value(rawArgs.length() > 500 ? rawArgs.substring(0, 500) + "..." : rawArgs)
                    .required(true)
                    .build());
        }

        return params;
    }

    /**
     * Resolve the effective argument map, unwrapping HTTP-style body wrapper if needed.
     * If args have a "body" key that is a JSON object AND all other keys are technical (hidden),
     * then the effective args are the parsed body contents.
     * Otherwise, the args are used as-is (flat format).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveEffectiveArgs(Map<String, Object> argMap) {
        // Check if this is HTTP-wrapped: all non-body keys are hidden
        boolean allOtherKeysHidden = argMap.entrySet().stream()
                .filter(e -> !"body".equalsIgnoreCase(e.getKey()))
                .allMatch(e -> HIDDEN_PARAMS.contains(e.getKey()));

        Object bodyVal = argMap.get("body");
        if (allOtherKeysHidden && bodyVal != null) {
            // HTTP-wrapped format: unwrap body
            if (bodyVal instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) bodyVal);
            }
            String bodyStr = bodyVal.toString();
            if (bodyStr.startsWith("{")) {
                Map<String, Object> parsed = parseJsonMap(bodyStr);
                if (!parsed.isEmpty()) return parsed;
            }
        }

        // Flat format: use args as-is (filter out hidden keys)
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : argMap.entrySet()) {
            if (!HIDDEN_PARAMS.contains(e.getKey())) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        return filtered;
    }

    private Map<String, Object> parseJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Safely convert a value (possibly a Map from Jackson) to a JSON string. */
    private String toJsonString(Object value) {
        if (value instanceof Map || value instanceof List) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    /**
     * Confluence: Space URL + Page Title + HTML content preview.
     * Handles both flat and nested arg formats:
     *   Flat: {"spaceId": "...", "title": "...", "body": {"representation": "storage", "value": "..."}}
     *   Nested: {"spaceId": "...", "title": "...", "body": {"storage": {"value": "..."}}}
     */
    private void addConfluenceParams(Map<String, Object> argMap, List<ToolApprovalRequest.ToolParameter> params) {
        // Space URL (user-friendly: paste a Confluence space URL instead of numeric Space ID)
        if (argMap.containsKey("spaceId")) {
            String confUrl = serviceBaseUrls.getOrDefault("confluence", "");
            String spaceUrl = confUrl.isEmpty() ? "" : confUrl + "/wiki/spaces/";
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.spaceUrl")
                    .displayName("Space URL")
                    .type("text")
                    .value(spaceUrl)
                    .required(true)
                    .build());
        }
        // Page title (editable)
        if (argMap.containsKey("title")) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.title")
                    .displayName("Page Title")
                    .type("text")
                    .value(String.valueOf(argMap.get("title")))
                    .required(true)
                    .build());
        }
        // Content preview: extract HTML from body (multiple possible structures)
        Object bodyContent = argMap.get("body");
        String html = null;
        if (bodyContent != null) {
            // Try: body.storage.value (Confluence v1)
            html = extractNestedValue(bodyContent, "storage", "value");
            // Try: body.value (Confluence v2)
            if (html == null) html = extractNestedValue(bodyContent, "value");
            // Try: body is a string (plain HTML)
            if (html == null && bodyContent instanceof String) {
                String bodyStr = (String) bodyContent;
                if (!bodyStr.isBlank()) html = bodyStr;
            }
        }
        // Also check "content" key (some MCP tools use "content" instead of "body")
        if (html == null) {
            Object content = argMap.get("content");
            if (content instanceof String && !((String) content).isBlank()) {
                html = (String) content;
            }
        }
        if (html != null) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.content_preview")
                    .displayName("Content Preview")
                    .type("html")
                    .value(html)
                    .required(true)
                    .build());
        }

        log.info("[ToolApproval] Confluence params built: hasSpaceId={}, hasTitle={}, hasContent={}",
                argMap.containsKey("spaceId"), argMap.containsKey("title"), html != null);
    }

    /** Slack: channel (user-editable text) + message text */
    @SuppressWarnings("unchecked")
    private void addSlackBodyParams(Map<String, Object> bodyMap, List<ToolApprovalRequest.ToolParameter> params) {
        // Channel/DM: autocomplete text input with API-backed suggestions.
        // If bot token has channels:read / users:read, autocomplete shows suggestions.
        // Otherwise, user types channel name or ID manually (graceful fallback).
        String channelKey = bodyMap.containsKey("channel_id") ? "channel_id"
                : bodyMap.containsKey("channel") ? "channel" : null;
        log.info("[ToolApproval] Slack channelKey='{}', bodyMap keys={}", channelKey, bodyMap.keySet());
        String channelValue = channelKey != null ? String.valueOf(bodyMap.get(channelKey)) : "";
        params.add(ToolApprovalRequest.ToolParameter.builder()
                .name("body." + (channelKey != null ? channelKey : "channel_id"))
                .displayName("Channel / DM")
                .type("autocomplete")
                .value(channelValue)
                .optionsEndpoint("/api/ai/tools/slack/channels")
                .required(true)
                .build());
        log.info("[ToolApproval] Added Slack channel autocomplete param: value={}", channelValue);

        // Slack messages can use "blocks" (Block Kit JSON) or plain "text".
        // If blocks exist, convert to HTML preview + editable plain text fallback.
        Object blocksObj = bodyMap.get("blocks");
        String text = getStringField(bodyMap, "text");
        if (text == null) text = getStringField(bodyMap, "message");

        if (blocksObj instanceof List) {
            // Convert Block Kit to HTML for visual preview
            String html = slackBlocksToHtml((List<Object>) blocksObj);
            if (!html.isBlank()) {
                params.add(ToolApprovalRequest.ToolParameter.builder()
                        .name("body.blocks_preview")
                        .displayName("Message Preview")
                        .type("html")
                        .value(html)
                        .required(false)
                        .build());
            }
            // Also provide editable plain text (extracted from blocks)
            String plainText = slackBlocksToPlainText((List<Object>) blocksObj);
            if (plainText != null && !plainText.isBlank()) {
                params.add(ToolApprovalRequest.ToolParameter.builder()
                        .name("body.text")
                        .displayName("Message (editable)")
                        .type("textarea")
                        .value(plainText)
                        .required(true)
                        .build());
            }
        } else if (text != null) {
            // Plain text message
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.text")
                    .displayName("Message")
                    .type("textarea")
                    .value(text)
                    .required(true)
                    .build());
        }
    }

    /**
     * Convert Slack Block Kit JSON to HTML for visual preview in approval UI.
     * Handles common block types: section, header, divider, context, rich_text.
     */
    @SuppressWarnings("unchecked")
    private String slackBlocksToHtml(List<Object> blocks) {
        StringBuilder html = new StringBuilder();
        for (Object block : blocks) {
            if (!(block instanceof Map)) continue;
            Map<String, Object> b = (Map<String, Object>) block;
            String type = String.valueOf(b.getOrDefault("type", ""));

            switch (type) {
                case "header": {
                    Map<String, Object> textObj = b.get("text") instanceof Map ? (Map<String, Object>) b.get("text") : null;
                    if (textObj != null) {
                        html.append("<h3>").append(escapeHtml(String.valueOf(textObj.getOrDefault("text", "")))).append("</h3>");
                    }
                    break;
                }
                case "section": {
                    Map<String, Object> textObj = b.get("text") instanceof Map ? (Map<String, Object>) b.get("text") : null;
                    if (textObj != null) {
                        String content = String.valueOf(textObj.getOrDefault("text", ""));
                        String textType = String.valueOf(textObj.getOrDefault("type", "plain_text"));
                        html.append("<p>").append("mrkdwn".equals(textType) ? slackMrkdwnToHtml(content) : escapeHtml(content)).append("</p>");
                    }
                    // Section fields (key-value pairs)
                    Object fields = b.get("fields");
                    if (fields instanceof List) {
                        html.append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:4px 16px;'>");
                        for (Object field : (List<Object>) fields) {
                            if (field instanceof Map) {
                                Map<String, Object> f = (Map<String, Object>) field;
                                String fText = String.valueOf(f.getOrDefault("text", ""));
                                String fType = String.valueOf(f.getOrDefault("type", "plain_text"));
                                html.append("<div>").append("mrkdwn".equals(fType) ? slackMrkdwnToHtml(fText) : escapeHtml(fText)).append("</div>");
                            }
                        }
                        html.append("</div>");
                    }
                    break;
                }
                case "divider":
                    html.append("<hr style='margin:8px 0;border-color:#ddd;'/>");
                    break;
                case "context": {
                    Object elements = b.get("elements");
                    if (elements instanceof List) {
                        html.append("<div style='font-size:12px;color:#666;'>");
                        for (Object elem : (List<Object>) elements) {
                            if (elem instanceof Map) {
                                Map<String, Object> e = (Map<String, Object>) elem;
                                String eText = String.valueOf(e.getOrDefault("text", ""));
                                html.append("<span>").append(escapeHtml(eText)).append("  </span>");
                            }
                        }
                        html.append("</div>");
                    }
                    break;
                }
                case "rich_text": {
                    Object elements = b.get("elements");
                    if (elements instanceof List) {
                        for (Object elem : (List<Object>) elements) {
                            if (elem instanceof Map) {
                                html.append(slackRichTextElementToHtml((Map<String, Object>) elem));
                            }
                        }
                    }
                    break;
                }
                default:
                    // Unknown block type: skip
                    break;
            }
        }
        return html.toString();
    }

    /**
     * Convert Slack rich_text element to HTML.
     */
    @SuppressWarnings("unchecked")
    private String slackRichTextElementToHtml(Map<String, Object> element) {
        String type = String.valueOf(element.getOrDefault("type", ""));
        Object elements = element.get("elements");
        if (!(elements instanceof List)) return "";

        StringBuilder html = new StringBuilder();
        switch (type) {
            case "rich_text_section":
                html.append("<p>");
                for (Object e : (List<Object>) elements) {
                    if (e instanceof Map) {
                        html.append(slackRichTextInlineToHtml((Map<String, Object>) e));
                    }
                }
                html.append("</p>");
                break;
            case "rich_text_list": {
                String style = String.valueOf(element.getOrDefault("style", "bullet"));
                String tag = "ordered".equals(style) ? "ol" : "ul";
                html.append("<").append(tag).append(">");
                for (Object item : (List<Object>) elements) {
                    if (item instanceof Map) {
                        Map<String, Object> listItem = (Map<String, Object>) item;
                        Object innerElements = listItem.get("elements");
                        html.append("<li>");
                        if (innerElements instanceof List) {
                            for (Object ie : (List<Object>) innerElements) {
                                if (ie instanceof Map) {
                                    html.append(slackRichTextInlineToHtml((Map<String, Object>) ie));
                                }
                            }
                        }
                        html.append("</li>");
                    }
                }
                html.append("</").append(tag).append(">");
                break;
            }
            case "rich_text_preformatted":
                html.append("<pre><code>");
                for (Object e : (List<Object>) elements) {
                    if (e instanceof Map) {
                        html.append(escapeHtml(String.valueOf(((Map<String, Object>) e).getOrDefault("text", ""))));
                    }
                }
                html.append("</code></pre>");
                break;
            case "rich_text_quote":
                html.append("<blockquote>");
                for (Object e : (List<Object>) elements) {
                    if (e instanceof Map) {
                        html.append(slackRichTextInlineToHtml((Map<String, Object>) e));
                    }
                }
                html.append("</blockquote>");
                break;
        }
        return html.toString();
    }

    /**
     * Convert Slack rich_text inline element to HTML.
     */
    @SuppressWarnings("unchecked")
    private String slackRichTextInlineToHtml(Map<String, Object> element) {
        String type = String.valueOf(element.getOrDefault("type", ""));
        switch (type) {
            case "text": {
                String text = escapeHtml(String.valueOf(element.getOrDefault("text", "")));
                Map<String, Object> style = element.get("style") instanceof Map ? (Map<String, Object>) element.get("style") : null;
                if (style != null) {
                    if (Boolean.TRUE.equals(style.get("bold"))) text = "<strong>" + text + "</strong>";
                    if (Boolean.TRUE.equals(style.get("italic"))) text = "<em>" + text + "</em>";
                    if (Boolean.TRUE.equals(style.get("code"))) text = "<code>" + text + "</code>";
                    if (Boolean.TRUE.equals(style.get("strike"))) text = "<s>" + text + "</s>";
                }
                return text;
            }
            case "link": {
                String url = String.valueOf(element.getOrDefault("url", "#"));
                String linkText = element.containsKey("text")
                        ? escapeHtml(String.valueOf(element.get("text")))
                        : escapeHtml(url);
                return "<a href=\"" + escapeHtml(url) + "\">" + linkText + "</a>";
            }
            case "emoji":
                return ":" + element.getOrDefault("name", "") + ":";
            default:
                return escapeHtml(String.valueOf(element.getOrDefault("text", "")));
        }
    }

    /**
     * Convert basic Slack mrkdwn to HTML.
     * Handles: *bold*, _italic_, ~strike~, `code`, ```preformatted```, links.
     */
    private String slackMrkdwnToHtml(String mrkdwn) {
        if (mrkdwn == null) return "";
        String text = escapeHtml(mrkdwn);
        // Bold: *text*
        text = text.replaceAll("\\*([^*]+)\\*", "<strong>$1</strong>");
        // Italic: _text_
        text = text.replaceAll("_([^_]+)_", "<em>$1</em>");
        // Strikethrough: ~text~
        text = text.replaceAll("~([^~]+)~", "<s>$1</s>");
        // Code: `text`
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");
        // Newlines
        text = text.replace("\n", "<br/>");
        return text;
    }

    /**
     * Extract plain text from Slack Block Kit blocks for editable textarea.
     */
    @SuppressWarnings("unchecked")
    private String slackBlocksToPlainText(List<Object> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (!(block instanceof Map)) continue;
            Map<String, Object> b = (Map<String, Object>) block;
            String type = String.valueOf(b.getOrDefault("type", ""));

            switch (type) {
                case "header":
                case "section": {
                    Map<String, Object> textObj = b.get("text") instanceof Map ? (Map<String, Object>) b.get("text") : null;
                    if (textObj != null) {
                        sb.append(textObj.getOrDefault("text", "")).append("\n");
                    }
                    Object fields = b.get("fields");
                    if (fields instanceof List) {
                        for (Object field : (List<Object>) fields) {
                            if (field instanceof Map) {
                                sb.append(((Map<String, Object>) field).getOrDefault("text", "")).append("\n");
                            }
                        }
                    }
                    break;
                }
                case "rich_text": {
                    Object elements = b.get("elements");
                    if (elements instanceof List) {
                        for (Object elem : (List<Object>) elements) {
                            if (elem instanceof Map) {
                                sb.append(slackRichTextElementToPlainText((Map<String, Object>) elem));
                            }
                        }
                    }
                    break;
                }
                case "divider":
                    sb.append("---\n");
                    break;
            }
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private String slackRichTextElementToPlainText(Map<String, Object> element) {
        Object elements = element.get("elements");
        if (!(elements instanceof List)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object e : (List<Object>) elements) {
            if (e instanceof Map) {
                sb.append(((Map<String, Object>) e).getOrDefault("text", ""));
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Jira: project + issue type + summary + assignee + description (ADF → HTML preview) */
    @SuppressWarnings("unchecked")
    private void addJiraBodyParams(Map<String, Object> bodyMap, List<ToolApprovalRequest.ToolParameter> params) {
        Map<String, Object> fields = bodyMap.containsKey("fields")
                ? (Map<String, Object>) bodyMap.get("fields") : bodyMap;

        // Project: resolve numeric ID to project key/name via Jira API
        Object projectObj = fields.get("project");
        if (projectObj instanceof Map) {
            Map<String, Object> proj = (Map<String, Object>) projectObj;
            String projDisplay;
            if (proj.containsKey("key")) {
                projDisplay = String.valueOf(proj.get("key"));
            } else if (proj.containsKey("id")) {
                String projId = String.valueOf(proj.get("id"));
                projDisplay = resolveJiraProjectName(projId);
            } else {
                projDisplay = proj.toString();
            }
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.project")
                    .displayName("Project")
                    .type("text")
                    .value(projDisplay)
                    .required(true)
                    .build());
        }
        // Issue type: dropdown with Jira issue types
        Object issueType = fields.get("issuetype");
        String issueTypeDisplay = "";
        if (issueType instanceof Map) {
            Map<String, Object> it = (Map<String, Object>) issueType;
            if (it.containsKey("name")) {
                issueTypeDisplay = String.valueOf(it.get("name"));
            } else if (it.containsKey("id")) {
                issueTypeDisplay = resolveJiraIssueTypeName(String.valueOf(it.get("id")));
            }
        }
        // Resolve project key for issue type endpoint (project-specific types)
        String projectKeyForTypes = "";
        if (projectObj instanceof Map) {
            Map<String, Object> proj = (Map<String, Object>) projectObj;
            if (proj.containsKey("key")) {
                projectKeyForTypes = String.valueOf(proj.get("key"));
            } else if (proj.containsKey("id")) {
                projectKeyForTypes = String.valueOf(proj.get("id"));
            }
        }
        params.add(ToolApprovalRequest.ToolParameter.builder()
                .name("body.issuetype")
                .displayName("Issue Type")
                .type("dropdown")
                .value(issueTypeDisplay)
                .optionsEndpoint("/api/ai/tools/jira/issuetypes?project=" + projectKeyForTypes)
                .required(true)
                .build());
        // Summary (editable)
        if (fields.containsKey("summary")) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.summary")
                    .displayName("Summary")
                    .type("text")
                    .value(String.valueOf(fields.get("summary")))
                    .required(true)
                    .build());
        }
        // Assignee (searchable dropdown via Jira user search API)
        Object assigneeObj = fields.get("assignee");
        String assigneeAccountId = null;
        String assigneeDisplay = "";
        if (assigneeObj instanceof Map) {
            Map<String, Object> assignee = (Map<String, Object>) assigneeObj;
            assigneeAccountId = getStringField(assignee, "id");
            if (assigneeAccountId == null) assigneeAccountId = getStringField(assignee, "accountId");
            assigneeDisplay = assigneeAccountId != null ? assigneeAccountId : "";
        }
        // Always show assignee field (user can search/select)
        params.add(ToolApprovalRequest.ToolParameter.builder()
                .name("body.assignee")
                .displayName("Assignee")
                .type("dropdown")
                .value(assigneeDisplay)
                .optionsEndpoint("/api/ai/tools/jira/users")
                .required(true)
                .build());

        // Description: convert ADF JSON to HTML preview, or show plain text
        Object descObj = fields.get("description");
        if (descObj != null) {
            if (descObj instanceof Map) {
                // ADF (Atlassian Document Format) — convert to HTML for preview
                String html = adfToHtml((Map<String, Object>) descObj);
                params.add(ToolApprovalRequest.ToolParameter.builder()
                        .name("body.description_preview")
                        .displayName("Description")
                        .type("html")
                        .value(html)
                        .required(true)
                        .build());
            } else {
                String desc = descObj.toString()
                        .replace("\\n", "\n")  // Convert literal \n to actual newlines
                        .replace("\\t", "\t");
                if (!desc.isBlank()) {
                    String preview = desc.length() > 500 ? desc.substring(0, 500) + "..." : desc;
                    params.add(ToolApprovalRequest.ToolParameter.builder()
                            .name("body.description")
                            .displayName("Description")
                            .type("textarea")
                            .value(preview)
                            .required(true)
                            .build());
                }
            }
        }
    }

    /**
     * Convert Atlassian Document Format (ADF) to simple HTML for preview.
     * ADF structure: { type: "doc", content: [ { type: "paragraph", content: [...] }, ... ] }
     */
    @SuppressWarnings("unchecked")
    private String adfToHtml(Map<String, Object> adf) {
        Object content = adf.get("content");
        if (!(content instanceof List)) {
            // Fallback: try to render as JSON
            return "<pre>" + escapeHtml(toJsonString(adf)) + "</pre>";
        }
        StringBuilder html = new StringBuilder();
        for (Object node : (List<Object>) content) {
            if (node instanceof Map) {
                html.append(adfNodeToHtml((Map<String, Object>) node));
            }
        }
        return html.toString();
    }

    @SuppressWarnings("unchecked")
    private String adfNodeToHtml(Map<String, Object> node) {
        String type = String.valueOf(node.getOrDefault("type", ""));
        List<Object> children = node.get("content") instanceof List ? (List<Object>) node.get("content") : List.of();

        StringBuilder inner = new StringBuilder();
        for (Object child : children) {
            if (child instanceof Map) {
                inner.append(adfNodeToHtml((Map<String, Object>) child));
            }
        }

        switch (type) {
            case "doc": return inner.toString();
            case "paragraph": return "<p>" + inner + "</p>";
            case "heading": {
                int level = node.get("attrs") instanceof Map
                        ? ((Number) ((Map<String, Object>) node.get("attrs")).getOrDefault("level", 3)).intValue()
                        : 3;
                return "<h" + level + ">" + inner + "</h" + level + ">";
            }
            case "bulletList": return "<ul>" + inner + "</ul>";
            case "orderedList": return "<ol>" + inner + "</ol>";
            case "listItem": return "<li>" + inner + "</li>";
            case "blockquote": return "<blockquote>" + inner + "</blockquote>";
            case "codeBlock": return "<pre><code>" + inner + "</code></pre>";
            case "rule": return "<hr/>";
            case "table": return "<table>" + inner + "</table>";
            case "tableRow": return "<tr>" + inner + "</tr>";
            case "tableHeader": return "<th>" + inner + "</th>";
            case "tableCell": return "<td>" + inner + "</td>";
            case "text": {
                String text = escapeHtml(String.valueOf(node.getOrDefault("text", "")));
                // Apply marks (bold, italic, link, code, etc.)
                Object marks = node.get("marks");
                if (marks instanceof List) {
                    for (Object mark : (List<Object>) marks) {
                        if (mark instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) mark;
                            String markType = String.valueOf(m.getOrDefault("type", ""));
                            switch (markType) {
                                case "strong": text = "<strong>" + text + "</strong>"; break;
                                case "em": text = "<em>" + text + "</em>"; break;
                                case "code": text = "<code>" + text + "</code>"; break;
                                case "link": {
                                    Map<String, Object> attrs = m.get("attrs") instanceof Map ? (Map<String, Object>) m.get("attrs") : Map.of();
                                    String href = String.valueOf(attrs.getOrDefault("href", "#"));
                                    text = "<a href=\"" + escapeHtml(href) + "\">" + text + "</a>";
                                    break;
                                }
                            }
                        }
                    }
                }
                return text;
            }
            case "hardBreak": return "<br/>";
            default: return inner.toString();
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** GitHub: repo + title + body text + labels */
    private void addGitHubBodyParams(Map<String, Object> bodyMap, List<ToolApprovalRequest.ToolParameter> params) {
        // Repo (editable destination)
        String repo = getStringField(bodyMap, "repo");
        if (repo == null) repo = getStringField(bodyMap, "repository");
        if (repo != null) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.repo")
                    .displayName("Repository")
                    .type("text")
                    .value(repo)
                    .required(true)
                    .build());
        }
        // Title (editable)
        if (bodyMap.containsKey("title")) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.title")
                    .displayName("Title")
                    .type("text")
                    .value(String.valueOf(bodyMap.get("title")))
                    .required(true)
                    .build());
        }
        // Content (editable)
        String bodyText = getStringField(bodyMap, "body");
        if (bodyText != null) {
            String preview = bodyText.length() > 500 ? bodyText.substring(0, 500) + "..." : bodyText;
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.body")
                    .displayName("Content")
                    .type("textarea")
                    .value(preview)
                    .required(true)
                    .build());
        }
        // Labels
        Object labels = bodyMap.get("labels");
        if (labels instanceof List) {
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body.labels")
                    .displayName("Labels")
                    .type("text")
                    .value(labels.toString())
                    .required(true)
                    .build());
        }
    }

    /** Generic fallback: show all non-empty fields from body */
    private void addGenericBodyParams(Map<String, Object> bodyMap, List<ToolApprovalRequest.ToolParameter> params) {
        for (Map.Entry<String, Object> entry : bodyMap.entrySet()) {
            String val = entry.getValue() != null ? entry.getValue().toString() : "";
            if (val.isBlank()) continue;
            String preview = val.length() > 300 ? val.substring(0, 300) + "..." : val;
            params.add(ToolApprovalRequest.ToolParameter.builder()
                    .name("body." + entry.getKey())
                    .displayName(humanize(entry.getKey()))
                    .type(preview.length() > 100 ? "textarea" : "text")
                    .value(preview)
                    .required(true)
                    .build());
        }
    }

    /**
     * Resolve Jira project ID to project key (e.g., "10001" → "PROJ").
     * Falls back to the raw ID if the API call fails.
     */
    private String resolveJiraProjectName(String projectId) {
        try {
            String baseUrl = jiraCredentials.get("baseUrl");
            String username = jiraCredentials.get("username");
            String apiToken = jiraCredentials.get("apiToken");
            if (baseUrl == null || apiToken == null) return projectId;

            String auth = Base64.getEncoder().encodeToString((username + ":" + apiToken).getBytes());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/api/3/project/" + projectId))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> proj = objectMapper.readValue(response.body(),
                        new TypeReference<Map<String, Object>>() {});
                String key = (String) proj.get("key");
                String name = (String) proj.get("name");
                return key != null ? key + " (" + name + ")" : projectId;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve Jira project ID {}: {}", projectId, e.getMessage());
        }
        return projectId;
    }

    /**
     * Resolve Jira issue type ID to name (e.g., "10001" → "Task").
     * Falls back to the raw ID if the API call fails.
     */
    private String resolveJiraIssueTypeName(String issueTypeId) {
        try {
            String baseUrl = jiraCredentials.get("baseUrl");
            String username = jiraCredentials.get("username");
            String apiToken = jiraCredentials.get("apiToken");
            if (baseUrl == null || apiToken == null) return issueTypeId;

            String auth = Base64.getEncoder().encodeToString((username + ":" + apiToken).getBytes());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/api/3/issuetype/" + issueTypeId))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> it = objectMapper.readValue(response.body(),
                        new TypeReference<Map<String, Object>>() {});
                String name = (String) it.get("name");
                return name != null ? name : issueTypeId;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve Jira issue type ID {}: {}", issueTypeId, e.getMessage());
        }
        return issueTypeId;
    }

    private String getStringField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    /** Extract a nested value like body.storage.value or body.value */
    private String extractNestedValue(Object obj, String... keys) {
        Object current = obj;
        for (String key : keys) {
            if (current instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) current;
                current = map.get(key);
            } else {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }

    private Map<String, Object> parseArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(rawArgs, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String extractFromArgs(Map<String, Object> args, String rawArgs, String key) {
        // Direct top-level
        if (args.containsKey(key)) {
            return String.valueOf(args.get(key));
        }
        // Try inside "body" (nested JSON or Map)
        Object body = args.get("body");
        if (body != null) {
            String bodyStr;
            if (body instanceof Map) {
                try {
                    bodyStr = objectMapper.writeValueAsString(body);
                } catch (Exception e) {
                    bodyStr = body.toString();
                }
            } else {
                bodyStr = body.toString();
            }
            Pattern p = key.equals("title") ? TITLE_PATTERN : CHANNEL_PATTERN;
            Matcher m = p.matcher(bodyStr);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String detectAction(String toolName) {
        if (toolName.contains("create") || toolName.contains("post")) return "Create";
        if (toolName.contains("put") || toolName.contains("update")) return "Update";
        if (toolName.contains("delete") || toolName.contains("remove")) return "Delete";
        if (toolName.contains("send")) return "Send message";
        return "Execute";
    }

    private String detectService(String toolName) {
        if (toolName.contains("conf")) return "Confluence";
        if (toolName.contains("slack")) return "Slack";
        if (toolName.contains("jira")) return "Jira";
        if (toolName.contains("github") || toolName.contains("gh")) return "GitHub";
        return null;
    }

    private ToolExecutionRequest applyModifiedParameters(ToolExecutionRequest original,
                                                          Map<String, String> modified) {
        try {
            String args = original.arguments();
            if (args != null && !args.isBlank()) {
                Map<String, Object> argMap = objectMapper.readValue(args,
                        new TypeReference<Map<String, Object>>() {});

                // Determine if args are HTTP-wrapped (body contains the real params)
                // or flat (spaceId/title at top level)
                boolean isHttpWrapped = argMap.containsKey("body") &&
                        argMap.entrySet().stream()
                                .filter(e -> !"body".equalsIgnoreCase(e.getKey()))
                                .allMatch(e -> HIDDEN_PARAMS.contains(e.getKey()));

                for (Map.Entry<String, String> entry : modified.entrySet()) {
                    String key = entry.getKey();

                    if (key.startsWith("body.")) {
                        String nestedKey = key.substring("body.".length());

                        // Special: body.spaceUrl → parse URL, resolve Space Key → Space ID + parentId
                        if ("spaceUrl".equals(nestedKey)) {
                            String spaceUrl = entry.getValue();
                            ConfluenceUrlInfo urlInfo = parseConfluenceUrl(spaceUrl);
                            if (urlInfo != null && urlInfo.spaceId != null) {
                                if (isHttpWrapped) {
                                    // HTTP-wrapped: put spaceId inside the body object
                                    Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                                    bodyMap.put("spaceId", urlInfo.spaceId);
                                    if (urlInfo.parentId != null) bodyMap.put("parentId", urlInfo.parentId);
                                    argMap.put("body", bodyMap);
                                } else {
                                    // Flat: put spaceId at top level
                                    argMap.put("spaceId", urlInfo.spaceId);
                                    if (urlInfo.parentId != null) argMap.put("parentId", urlInfo.parentId);
                                }
                                log.info("[ToolApproval] Resolved spaceUrl '{}' → spaceId '{}', parentId '{}'",
                                        spaceUrl, urlInfo.spaceId, urlInfo.parentId);
                            } else {
                                log.warn("[ToolApproval] Could not resolve spaceUrl '{}', keeping original spaceId", spaceUrl);
                            }
                        } else if ("title".equals(nestedKey) || "summary".equals(nestedKey)) {
                            if (isHttpWrapped) {
                                Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                                bodyMap.put(nestedKey, entry.getValue());
                                argMap.put("body", bodyMap);
                            } else {
                                argMap.put(nestedKey, entry.getValue());
                            }
                        } else if ("issuetype".equals(nestedKey)) {
                            // Jira issue type: convert name to {"name": "Task"} structure
                            String typeName = entry.getValue();
                            if (typeName != null && !typeName.isBlank()) {
                                Map<String, Object> issueTypeObj = new LinkedHashMap<>();
                                issueTypeObj.put("name", typeName);
                                if (isHttpWrapped) {
                                    Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> fields = bodyMap.containsKey("fields")
                                            ? (Map<String, Object>) bodyMap.get("fields") : bodyMap;
                                    fields.put("issuetype", issueTypeObj);
                                    argMap.put("body", bodyMap);
                                } else {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> fields = argMap.containsKey("fields")
                                            ? (Map<String, Object>) argMap.get("fields") : argMap;
                                    fields.put("issuetype", issueTypeObj);
                                }
                                log.info("[ToolApproval] Set Jira issue type: {}", typeName);
                            }
                        } else if ("assignee".equals(nestedKey)) {
                            // Jira assignee: convert accountId to {"id": "accountId"} structure
                            String accountId = entry.getValue();
                            if (accountId != null && !accountId.isBlank()) {
                                Map<String, Object> assigneeObj = new LinkedHashMap<>();
                                assigneeObj.put("id", accountId);
                                if (isHttpWrapped) {
                                    Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> fields = bodyMap.containsKey("fields")
                                            ? (Map<String, Object>) bodyMap.get("fields") : bodyMap;
                                    fields.put("assignee", assigneeObj);
                                    argMap.put("body", bodyMap);
                                } else {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> fields = argMap.containsKey("fields")
                                            ? (Map<String, Object>) argMap.get("fields") : argMap;
                                    fields.put("assignee", assigneeObj);
                                }
                                log.info("[ToolApproval] Set Jira assignee accountId: {}", accountId);
                            }
                        } else if ("text".equals(nestedKey)) {
                            // Slack text: if user edited the message, set text and remove blocks
                            // so Slack sends plain text instead of Block Kit
                            if (isHttpWrapped) {
                                Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                                bodyMap.put("text", entry.getValue());
                                bodyMap.remove("blocks"); // Remove blocks, send as plain text
                                argMap.put("body", bodyMap);
                            } else {
                                argMap.put("text", entry.getValue());
                                argMap.remove("blocks");
                            }
                            log.info("[ToolApproval] Set Slack text (removed blocks if present)");
                        } else if ("channel".equals(nestedKey) || "channel_id".equals(nestedKey)) {
                            // Slack channel: update the channel ID (MCP uses "channel_id")
                            if (isHttpWrapped) {
                                Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                                bodyMap.put(nestedKey, entry.getValue());
                                argMap.put("body", bodyMap);
                            } else {
                                argMap.put(nestedKey, entry.getValue());
                            }
                            log.info("[ToolApproval] Set Slack channel ({}): {}", nestedKey, entry.getValue());
                        } else if ("description_preview".equals(nestedKey) || "blocks_preview".equals(nestedKey)) {
                            // Skip — preview params are read-only, not editable
                        } else if (isHttpWrapped) {
                            // Other body.* keys go into body object
                            Map<String, Object> bodyMap = getOrParseBodyMap(argMap);
                            if (bodyMap.containsKey(nestedKey)) {
                                bodyMap.put(nestedKey, entry.getValue());
                            }
                            argMap.put("body", bodyMap);
                        }
                    } else if (argMap.containsKey(key)) {
                        argMap.put(key, entry.getValue());
                    }
                }

                String newArgs = objectMapper.writeValueAsString(argMap);
                log.info("[ToolApproval] Modified args: {}", newArgs.length() > 500 ?
                        newArgs.substring(0, 500) + "..." : newArgs);
                return ToolExecutionRequest.builder()
                        .id(original.id())
                        .name(original.name())
                        .arguments(newArgs)
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to apply modified parameters, using original: {}", e.getMessage());
        }
        return original;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrParseBodyMap(Map<String, Object> argMap) {
        Object bodyObj = argMap.get("body");
        if (bodyObj instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) bodyObj);
        }
        if (bodyObj != null) {
            try {
                return objectMapper.readValue(bodyObj.toString(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * Enrich tool execution results with useful URLs.
     * For Confluence page creation, appends the page URL so the AI can include it in its response.
     *
     * MCP result can be JSON ({"id": 226132304}) or jq-filtered text (id: "226328941"\ntitle: ...).
     */
    /**
     * Extract a user-friendly error message from a JSON tool result.
     */
    private String extractErrorMessage(String result) {
        try {
            var map = objectMapper.readValue(result, java.util.Map.class);
            String error = (String) map.get("error");
            String needed = map.get("needed") != null ? String.valueOf(map.get("needed")) : null;
            if (error != null && needed != null) {
                return error + " (needed: " + needed + ")";
            }
            return error != null ? error : "Unknown error";
        } catch (Exception e) {
            // Not JSON, return truncated raw result
            return result.length() > 200 ? result.substring(0, 200) + "..." : result;
        }
    }

    private String enrichResultWithUrl(String toolName, String args, String result) {
        if (result == null || result.startsWith("Error")) return result;
        String lower = toolName != null ? toolName.toLowerCase() : "";

        // Confluence: extract page ID from result and build URL
        if (lower.contains("conf") && (lower.contains("create") || lower.contains("post"))) {
            String confUrl = serviceBaseUrls.get("confluence");
            if (confUrl == null || confUrl.isBlank()) return result;

            String pageId = extractPageIdFromResult(result);
            if (pageId == null) return result;

            String spaceKey = lastResolvedSpaceKey;
            String pageUrl;
            if (spaceKey != null && !spaceKey.isBlank()) {
                pageUrl = confUrl + "/wiki/spaces/" + spaceKey + "/pages/" + pageId;
            } else {
                pageUrl = confUrl + "/wiki/pages/viewpage.action?pageId=" + pageId;
            }
            log.info("[ToolApproval] Enriched result with page URL: {}", pageUrl);
            return result + "\n\nPage URL: " + pageUrl;
        }
        return result;
    }

    /** Extract page ID from MCP result — handles JSON and jq text output. */
    private String extractPageIdFromResult(String result) {
        // Try JSON: {"id": 226132304, ...}
        try {
            Map<String, Object> resultMap = objectMapper.readValue(result,
                    new TypeReference<Map<String, Object>>() {});
            Object id = resultMap.get("id");
            if (id != null) return id.toString().replaceAll("\"", "");
        } catch (Exception ignored) {}

        // Try jq text: id: "226328941" or id: 226328941
        Matcher m = Pattern.compile("(?:^|\\n)id:\\s*\"?(\\d+)\"?").matcher(result);
        if (m.find()) return m.group(1);

        // Try "id" key in YAML-like format
        Matcher m2 = Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(result);
        if (m2.find()) return m2.group(1);

        return null;
    }

    /** Parsed result from a Confluence URL. */
    private static class ConfluenceUrlInfo {
        String spaceId;   // resolved numeric space ID
        String spaceKey;  // space key from URL (e.g., "NFTMetaverse")
        String parentId;  // page ID from URL (used as parent for child page creation)
    }

    /**
     * Parse a Confluence URL and resolve Space Key → Space ID.
     * Also extracts page ID as parentId for child page creation.
     *
     * Accepts:
     *   https://xxx.atlassian.net/wiki/spaces/NFTMetaverse/pages/29596412
     *   https://xxx.atlassian.net/wiki/spaces/NFTMetaverse
     *   NFTMetaverse (plain space key)
     */
    private ConfluenceUrlInfo parseConfluenceUrl(String spaceUrl) {
        if (spaceUrl == null || spaceUrl.isBlank()) return null;

        String spaceKey = null;
        String pageId = null;

        // Try: /wiki/spaces/{SpaceKey}/pages/{PageId}
        Pattern fullPattern = Pattern.compile("/wiki/spaces/([^/]+)/pages/(\\d+)");
        Matcher mFull = fullPattern.matcher(spaceUrl);
        if (mFull.find()) {
            spaceKey = mFull.group(1);
            pageId = mFull.group(2);
        } else {
            // Try: /wiki/spaces/{SpaceKey}
            Pattern spacePattern = Pattern.compile("/wiki/spaces/([^/]+)");
            Matcher mSpace = spacePattern.matcher(spaceUrl);
            if (mSpace.find()) {
                spaceKey = mSpace.group(1);
            } else if (!spaceUrl.contains("/") && !spaceUrl.contains(".")) {
                // Plain space key (e.g., "NFTMetaverse")
                spaceKey = spaceUrl.trim();
            }
        }

        if (spaceKey == null) {
            log.warn("[ToolApproval] Cannot parse space key from URL: {}", spaceUrl);
            return null;
        }

        // Resolve Space Key → Space ID via Confluence REST API
        String baseUrl = confluenceCredentials.get("baseUrl");
        String username = confluenceCredentials.get("username");
        String apiToken = confluenceCredentials.get("apiToken");
        if (baseUrl == null || username == null || apiToken == null) {
            log.warn("[ToolApproval] Confluence credentials not available for space resolution");
            return null;
        }

        try {
            String apiUrl = baseUrl + "/wiki/api/v2/spaces?keys=" + spaceKey;
            String auth = Base64.getEncoder().encodeToString((username + ":" + apiToken).getBytes());

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> body = objectMapper.readValue(response.body(),
                        new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
                if (results != null && !results.isEmpty()) {
                    ConfluenceUrlInfo info = new ConfluenceUrlInfo();
                    info.spaceId = String.valueOf(results.get(0).get("id"));
                    info.spaceKey = spaceKey;
                    info.parentId = pageId;
                    lastResolvedSpaceKey = spaceKey;
                    return info;
                }
            }
            log.warn("[ToolApproval] Confluence API returned {}: {}", response.statusCode(), response.body());
        } catch (Exception e) {
            log.warn("[ToolApproval] Failed to resolve space key '{}': {}", spaceKey, e.getMessage());
        }
        return null;
    }

    /** Extract hostname from URL (e.g., "https://xxx.atlassian.net" → "xxx.atlassian.net") */
    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private String humanize(String name) {
        if (name == null) return "";
        // "default_api:conf_create_page" → "Conf Create Page"
        String clean = name.contains(":") ? name.substring(name.lastIndexOf(':') + 1) : name;
        String[] parts = clean.split("[_\\-]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
