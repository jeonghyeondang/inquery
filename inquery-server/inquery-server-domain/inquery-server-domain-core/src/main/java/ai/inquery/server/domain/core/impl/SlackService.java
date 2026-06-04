package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.core.impl.SlackSearchResult;
import ai.inquery.server.domain.core.impl.SlackThreadMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Slack service for search and message posting via direct REST API.
 */
public class SlackService extends AbstractSearchService {

    private final UserAIConfigDO userConfig;
    private final String token;

    public SlackService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl("https://slack.com/api")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build());
        this.userConfig = userConfig;
        this.token = userConfig.getSlackUserToken();
    }

    public SlackService(UserAIConfigSaveParam config) {
        super(WebClient.builder()
            .baseUrl("https://slack.com/api")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build());
        this.userConfig = null;
        this.token = config.getSlackUserToken();
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    /**
     * Post a message to a Slack channel or DM.
     * @return permalink URL of the posted message, or null on failure
     */
    public String postMessage(String channelId, String text) {
        if (!isConfigured()) {
            logger.warn("Slack token not configured for posting messages");
            return null;
        }
        try {
            String bodyJson = objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
                put("channel", channelId);
                put("text", text);
            }});

            WebClient client = WebClient.builder()
                    .baseUrl("https://slack.com/api")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            String response = client.post()
                    .uri("/chat.postMessage")
                    .bodyValue(bodyJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                if (root.path("ok").asBoolean(false)) {
                    String channel = root.path("channel").asText(channelId);
                    String ts = root.path("ts").asText("");
                    String permalink = root.path("message").path("permalink").asText("");
                    logger.info("Slack message posted: channel={}, ts={}", channel, ts);
                    return permalink.isEmpty() ? "Message sent to channel " + channel : permalink;
                } else {
                    String error = root.path("error").asText("unknown");
                    logger.error("Slack chat.postMessage failed: {}", error);
                    return null;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to post Slack message: {}", e.getMessage(), e);
        }
        return null;
    }

    public List<SlackSearchResult> searchMessages(String tableName, int maxResults) {
        List<SlackSearchResult> results = new ArrayList<>();

        if (!isConfigured()) {
            logger.warn("Slack token not configured for search");
            return results;
        }

        try {
            String searchQuery = extractTableName(tableName);
            logger.info("Slack search started: {} (query: {})", tableName, searchQuery);

            results = searchWithMessagesAPI(searchQuery, maxResults);

            logger.info("Slack search completed: {} results", results.size());
        } catch (Exception e) {
            logger.error("Slack search failed", e);
        }

        return results;
    }

    /**
     * Search Slack messages by keyword (for chat flow, not AI collection).
     */
    public List<SlackSearchResult> searchByKeyword(String keyword, int maxResults) {
        List<SlackSearchResult> results = new ArrayList<>();
        if (!isConfigured()) return results;
        try {
            results = searchWithMessagesAPI(keyword, maxResults);
        } catch (Exception e) {
            logger.error("Slack keyword search failed: {}", e.getMessage());
        }
        return results;
    }

    private List<SlackThreadMessage> fetchThreadMessages(String channelId, String threadTs) {
        List<SlackThreadMessage> threadMessages = new ArrayList<>();
        String cursor = null;
        int pageLimit = 100;

        try {
            do {
                MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
                params.add("channel", channelId);
                params.add("ts", threadTs);
                params.add("limit", String.valueOf(pageLimit));
                if (cursor != null && !cursor.isEmpty()) {
                    params.add("cursor", cursor);
                }

                WebClient configuredWebClient = WebClient.builder()
                    .baseUrl("https://slack.com/api")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .build();

                String response = configuredWebClient.post()
                    .uri("/conversations.replies")
                    .bodyValue(params)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), this::handleErrorResponse)
                    .bodyToMono(String.class)
                    .transform(this::withTimeoutAndRetry)
                    .onErrorResume(e -> {
                        logger.error("Failed to fetch Slack thread: {}", e.getMessage());
                        if (e.getMessage() != null && e.getMessage().contains("missing_scope")) {
                            logger.warn("conversations.replies requires User Token scopes channels:history or groups:history.");
                        }
                        return Mono.just("{\"ok\":false,\"messages\":[]}");
                    })
                    .block();

                if (response != null && !response.isEmpty()) {
                    JsonNode root = objectMapper.readTree(response);

                    logger.debug("conversations.replies response: ok={}, messages size={}",
                        root.path("ok").asBoolean(false),
                        root.path("messages").isArray() ? root.path("messages").size() : 0);

                    if (root.path("ok").asBoolean(false)) {
                        JsonNode messagesNode = root.path("messages");

                        logger.debug("Thread messages array size: {}", messagesNode.size());

                        boolean isFirst = (cursor == null);
                        int skippedFirst = 0;
                        for (JsonNode threadMsg : messagesNode) {
                            if (isFirst && skippedFirst == 0) {
                                skippedFirst++;
                                logger.debug("Skipping root message: ts={}", threadMsg.path("ts").asText(""));
                                continue;
                            }

                            String msgTs = threadMsg.path("ts").asText("");
                            String msgText = threadMsg.path("text").asText("");
                            String msgUser = threadMsg.path("user").asText("");

                            logger.debug("Adding thread message: ts={}, user={}, text length={}",
                                msgTs, msgUser, msgText.length());

                            SlackThreadMessage threadMessage = new SlackThreadMessage(msgTs, msgText, msgUser);
                            threadMessages.add(threadMessage);
                        }

                        JsonNode responseMetadata = root.path("response_metadata");
                        if (responseMetadata.has("next_cursor")) {
                            cursor = responseMetadata.path("next_cursor").asText("");
                            if (cursor.isEmpty()) {
                                cursor = null;
                            }
                        } else {
                            cursor = null;
                        }

                        logger.debug("Thread fetch progress: {} messages, next page: {}",
                            threadMessages.size(), cursor != null ? "yes" : "none");
                    } else {
                        String error = root.path("error").asText("");
                        // missing_scope is expected when scopes are missing — log at DEBUG
                        if ("missing_scope".equals(error)) {
                            logger.debug("conversations.replies missing_scope — expected when scopes are absent");
                        } else {
                            logger.warn("conversations.replies API error: {}", error);
                        }
                        cursor = null;
                    }
                } else {
                    cursor = null;
                }
            } while (cursor != null);

            logger.info("Fetched {} thread messages total", threadMessages.size());
        } catch (Exception e) {
            logger.error("Error fetching thread messages", e);
        }

        return threadMessages;
    }

    private List<SlackSearchResult> searchWithMessagesAPI(String searchQuery, int maxResults) {
        List<SlackSearchResult> results = new ArrayList<>();

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("query", searchQuery);
        params.add("count", String.valueOf(maxResults));
        params.add("sort", "score");
        params.add("sort_dir", "desc");

        WebClient configuredWebClient = WebClient.builder()
            .baseUrl("https://slack.com/api")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build();

        String response = configuredWebClient.post()
            .uri("/search.messages")
            .bodyValue(params)
            .retrieve()
            .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), this::handleErrorResponse)
            .bodyToMono(String.class)
            .transform(this::withTimeoutAndRetry)
            .onErrorResume(e -> {
                logger.error("Slack search.messages API call failed: {}", e.getMessage());
                return Mono.just("{\"ok\":false,\"messages\":{\"matches\":[]}}");
            })
            .block();

        if (response != null && !response.isEmpty()) {
            try {
                JsonNode root = objectMapper.readTree(response);

                if (root.path("ok").asBoolean(false)) {
                    JsonNode messagesNode = root.path("messages").path("matches");

                    for (JsonNode message : messagesNode) {
                        String channelId = message.path("channel").path("id").asText("");
                        String channelName = message.path("channel").path("name").asText("");
                        String messageTs = message.path("ts").asText("");
                        String messageText = message.path("text").asText("");
                        String userName = message.path("username").asText("");

                        logger.debug("Message response structure: {}", message.toString());

                        String teamId = root.path("team_id").asText("");
                        if (teamId.isEmpty()) {
                            teamId = channelId;
                        }
                        String timestampForUrl = messageTs.replace(".", "");
                        String messageUrl = String.format("https://app.slack.com/client/%s/%s/p%s",
                            teamId, channelId, timestampForUrl);

                        SlackSearchResult slackResult = new SlackSearchResult(
                            channelId, channelName, messageTs, messageText, messageUrl, userName
                        );

                        String threadRootTs = messageTs;

                        boolean hasThread = false;

                        if (message.has("num_replies")) {
                            int numReplies = message.path("num_replies").asInt(0);
                            hasThread = numReplies > 0;
                            logger.debug("Message num_replies: {}", numReplies);
                        }

                        if (!hasThread && message.has("reply_count")) {
                            int replyCount = message.path("reply_count").asInt(0);
                            hasThread = replyCount > 0;
                            logger.debug("Message reply_count: {}", replyCount);
                        }

                        if (!hasThread && message.has("thread_ts") && !message.path("thread_ts").isNull()) {
                            String threadTs = message.path("thread_ts").asText("");
                            if (!threadTs.isEmpty()) {
                                threadRootTs = threadTs;
                                hasThread = true;
                                logger.debug("Message is part of a thread: thread_ts={}", threadTs);
                            }
                        }

                        logger.debug("Attempting thread fetch: channelId={}, messageTs={}, threadRootTs={}, hasThread={}",
                            channelId, messageTs, threadRootTs, hasThread);
                        List<SlackThreadMessage> threadMessages = fetchThreadMessages(channelId, threadRootTs);
                        if (!threadMessages.isEmpty()) {
                            slackResult.setThreadMessages(threadMessages);
                            logger.info("Fetched {} thread messages", threadMessages.size());
                        } else {
                            logger.debug("No thread messages or fetch failed");
                        }

                        results.add(slackResult);
                    }
                    logger.debug("search.messages API returned {} results", results.size());
                } else {
                    String error = root.path("error").asText("");
                    logger.warn("Slack search.messages API error: {}", error);
                }
            } catch (Exception e) {
                logger.error("Error parsing search.messages response", e);
            }
        }

        return results;
    }
}



