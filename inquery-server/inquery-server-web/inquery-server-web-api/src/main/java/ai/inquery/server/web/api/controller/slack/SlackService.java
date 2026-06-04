package ai.inquery.server.web.api.controller.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.methods.request.files.FilesUploadV2Request;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.methods.response.files.FilesUploadV2Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Slack API Service for sending messages, updating messages, and uploading files.
 * Supports Slack Agents & Assistants API for thinking step UI.
 * Includes automatic retry with Retry-After header for 429 rate limit errors.
 */
@Slf4j
@Service
public class SlackService {

    private final Slack slack = Slack.getInstance();
    private static final int MAX_RETRIES = 2;
    private static final long DEFAULT_RETRY_AFTER_MS = 3000; // 3 seconds default if no Retry-After header

    /**
     * Execute a Slack API call with automatic retry on 429 rate limit errors.
     * Respects the Retry-After header from Slack's response.
     */
    private <T> T executeWithRetry(String methodName, SlackApiCall<T> apiCall) throws IOException, SlackApiException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return apiCall.execute();
            } catch (SlackApiException e) {
                if (e.getResponse() != null && e.getResponse().code() == 429 && attempt < MAX_RETRIES) {
                    long retryAfterMs = DEFAULT_RETRY_AFTER_MS;
                    String retryAfterHeader = e.getResponse().header("Retry-After");
                    if (retryAfterHeader != null) {
                        try {
                            retryAfterMs = Long.parseLong(retryAfterHeader.trim()) * 1000;
                        } catch (NumberFormatException ignored) {}
                    }
                    log.warn("[Slack] 429 rate limited on {} (attempt {}/{}), retrying after {}ms",
                            methodName, attempt + 1, MAX_RETRIES, retryAfterMs);
                    try {
                        Thread.sleep(retryAfterMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new IOException("Unexpected: exceeded max retries for " + methodName);
    }

    @FunctionalInterface
    private interface SlackApiCall<T> {
        T execute() throws IOException, SlackApiException;
    }

    /**
     * Set assistant thread status (thinking step UI).
     * This displays a special status indicator below the user's message.
     * Requires Slack app to be configured as an Agent/Assistant.
     * 
     * @param botToken Bot token
     * @param channelId Channel ID (DM channel)
     * @param threadTs Thread timestamp
     * @param status Status message to display (e.g., "Generating SQL...")
     * @return true if successful
     */
    public boolean setAssistantStatus(String botToken, String channelId, String threadTs, String status) {
        try {
            MethodsClient client = slack.methods(botToken);
            var response = client.assistantThreadsSetStatus(r -> r
                    .channelId(channelId)
                    .threadTs(threadTs)
                    .status(status)
            );
            if (response.isOk()) {
                log.debug("[Slack] Assistant status set: {}", status);
                return true;
            } else {
                log.warn("[Slack] Failed to set assistant status: {} (error: {})", status, response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.warn("[Slack] Error setting assistant status (may not be an Assistant app): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Add reaction to user's message to indicate processing (fallback for non-Assistant apps).
     * Returns true if successful.
     */
    public boolean addReaction(String botToken, String channelId, String messageTs, String emoji) {
        try {
            MethodsClient client = slack.methods(botToken);
            var response = client.reactionsAdd(r -> r
                    .channel(channelId)
                    .timestamp(messageTs)
                    .name(emoji)
            );
            if (response.isOk()) {
                log.debug("[Slack] Reaction '{}' added to message {}", emoji, messageTs);
                return true;
            } else {
                log.warn("[Slack] Failed to add reaction: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error adding reaction", e);
            return false;
        }
    }

    /**
     * Remove reaction from message.
     */
    public boolean removeReaction(String botToken, String channelId, String messageTs, String emoji) {
        try {
            MethodsClient client = slack.methods(botToken);
            var response = client.reactionsRemove(r -> r
                    .channel(channelId)
                    .timestamp(messageTs)
                    .name(emoji)
            );
            return response.isOk();
        } catch (IOException | SlackApiException e) {
            log.debug("[Slack] Error removing reaction (may not exist)", e);
            return false;
        }
    }

    /**
     * Send initial "Thinking..." message and return message timestamp for updates.
     * Use for DATA queries that take longer time.
     */
    public String sendThinkingMessage(String botToken, String channelId, String threadTs) {
        try {
            MethodsClient client = slack.methods(botToken);

            String initialText = ":loading-gif: Initializing...";

            ChatPostMessageRequest.ChatPostMessageRequestBuilder requestBuilder = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text(initialText);

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            final ChatPostMessageRequest request = requestBuilder.build();
            ChatPostMessageResponse response = executeWithRetry("chatPostMessage/thinking", () ->
                    client.chatPostMessage(request));

            if (response.isOk()) {
                log.info("[Slack] Thinking message sent to channel: {}, ts: {}", channelId, response.getTs());
                return response.getTs();
            } else {
                log.error("[Slack] Failed to send thinking message: {}", response.getError());
                return null;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error sending thinking message", e);
            return null;
        }
    }

    /**
     * Update thinking step message (KIRA-style progress UI).
     */
    public boolean updateThinkingStep(String botToken, String channelId, String messageTs, String step) {
        try {
            MethodsClient client = slack.methods(botToken);

            String text = ":loading-gif: " + step;
            log.info("[Slack] Updating thinking step: {} (ts: {})", step, messageTs);

            ChatUpdateResponse response = executeWithRetry("chatUpdate/thinkingStep", () ->
                    client.chatUpdate(ChatUpdateRequest.builder()
                            .channel(channelId)
                            .ts(messageTs)
                            .text(text)
                            .build()));

            if (response.isOk()) {
                log.info("[Slack] Thinking step updated successfully: {}", step);
                return true;
            } else {
                log.warn("[Slack] Failed to update thinking step '{}': {}", step, response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error updating thinking step: {}", step, e);
            return false;
        }
    }

    /**
     * Send final result message with analysis.
     */
    public boolean sendResultMessage(String botToken, String channelId, String threadTs, String text) {
        try {
            MethodsClient client = slack.methods(botToken);

            ChatPostMessageRequest.ChatPostMessageRequestBuilder requestBuilder = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text(text);

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            final ChatPostMessageRequest request = requestBuilder.build();
            ChatPostMessageResponse response = executeWithRetry("chatPostMessage/result", () ->
                    client.chatPostMessage(request));

            if (response.isOk()) {
                log.info("[Slack] Result message sent to channel: {}", channelId);
                return true;
            } else {
                log.error("[Slack] Failed to send result message: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error sending result message", e);
            return false;
        }
    }

    /**
     * Update the thinking message to show completion.
     */
    public boolean updateToCompleted(String botToken, String channelId, String messageTs, String title) {
        try {
            MethodsClient client = slack.methods(botToken);

            String text = ":white_check_mark: " + title;

            ChatUpdateResponse response = executeWithRetry("chatUpdate/completed", () ->
                    client.chatUpdate(ChatUpdateRequest.builder()
                            .channel(channelId)
                            .ts(messageTs)
                            .text(text)
                            .build()));

            return response.isOk();
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error updating to completed", e);
            return false;
        }
    }

    /**
     * Delete a message (e.g., thinking message after completion).
     */
    public boolean deleteMessage(String botToken, String channelId, String messageTs) {
        try {
            MethodsClient client = slack.methods(botToken);

            var response = client.chatDelete(com.slack.api.methods.request.chat.ChatDeleteRequest.builder()
                    .channel(channelId)
                    .ts(messageTs)
                    .build());

            if (response.isOk()) {
                log.debug("[Slack] Message deleted: {}", messageTs);
            } else {
                log.warn("[Slack] Failed to delete message: {}", response.getError());
            }
            return response.isOk();
        } catch (IOException | SlackApiException e) {
            log.warn("[Slack] Error deleting message: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Update a message with new text (replaces the entire message).
     * Used for simple responses where we want to replace thinking message with actual content.
     */
    public boolean updateMessage(String botToken, String channelId, String messageTs, String text) {
        try {
            MethodsClient client = slack.methods(botToken);

            ChatUpdateResponse response = executeWithRetry("chatUpdate/message", () ->
                    client.chatUpdate(ChatUpdateRequest.builder()
                            .channel(channelId)
                            .ts(messageTs)
                            .text(text)
                            .build()));

            if (response.isOk()) {
                log.debug("[Slack] Message updated successfully");
                return true;
            } else {
                log.warn("[Slack] Failed to update message: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error updating message", e);
            return false;
        }
    }

    /**
     * Upload chart image as PNG file.
     */
    public boolean uploadChartImage(String botToken, String channelId, String threadTs, 
                                    byte[] imageData, String filename, String title) {
        try {
            MethodsClient client = slack.methods(botToken);

            FilesUploadV2Request.FilesUploadV2RequestBuilder requestBuilder = FilesUploadV2Request.builder()
                    .channel(channelId)
                    .fileData(imageData)
                    .filename(filename != null ? filename : "chart.png")
                    .title(title != null ? title : "Chart")
                    .initialComment("📊 Chart result");

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            FilesUploadV2Response response = client.filesUploadV2(requestBuilder.build());

            if (response.isOk()) {
                log.info("[Slack] Chart image uploaded: {}", filename);
                return true;
            } else {
                log.error("[Slack] Failed to upload chart image: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error uploading chart image", e);
            return false;
        }
    }

    /**
     * Send chart image using Image Block (for QuickChart.io URLs).
     * This is more efficient than uploading - Slack fetches the image from the URL.
     */
    public boolean sendChartImageBlock(String botToken, String channelId, String threadTs,
                                       String imageUrl, String title, String altText) {
        try {
            MethodsClient client = slack.methods(botToken);

            // Build Image Block JSON
            String blocksJson = """
                [
                    {
                        "type": "image",
                        "title": {
                            "type": "plain_text",
                            "text": "%s"
                        },
                        "image_url": "%s",
                        "alt_text": "%s"
                    }
                ]
                """.formatted(
                    escapeJson(title != null ? title : "Chart"),
                    imageUrl,
                    escapeJson(altText != null ? altText : "Chart visualization")
                );

            ChatPostMessageRequest.ChatPostMessageRequestBuilder requestBuilder = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text("📊 " + (title != null ? title : "Chart")) // Fallback text
                    .blocksAsString(blocksJson);

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            ChatPostMessageResponse response = client.chatPostMessage(requestBuilder.build());

            if (response.isOk()) {
                log.info("[Slack] Chart image block sent: {}", title);
                return true;
            } else {
                log.error("[Slack] Failed to send chart image block: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error sending chart image block", e);
            return false;
        }
    }

    /**
     * Escape special characters for JSON string.
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Upload CSV data file.
     */
    public boolean uploadCsvFile(String botToken, String channelId, String threadTs,
                                 String csvContent, String filename, String title) {
        try {
            MethodsClient client = slack.methods(botToken);

            FilesUploadV2Request.FilesUploadV2RequestBuilder requestBuilder = FilesUploadV2Request.builder()
                    .channel(channelId)
                    .content(csvContent)
                    .filename(filename != null ? filename : "data.csv")
                    .title(title != null ? title : "Query Result")
                    .initialComment("📋 Data result (CSV)");

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            FilesUploadV2Response response = client.filesUploadV2(requestBuilder.build());

            if (response.isOk()) {
                log.info("[Slack] CSV file uploaded: {}", filename);
                return true;
            } else {
                log.error("[Slack] Failed to upload CSV file: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error uploading CSV file", e);
            return false;
        }
    }

    /**
     * Send error message.
     */
    public boolean sendErrorMessage(String botToken, String channelId, String threadTs, String error) {
        try {
            MethodsClient client = slack.methods(botToken);

            String text = ":x: An error occurred\n\n```" + error + "```";

            ChatPostMessageRequest.ChatPostMessageRequestBuilder requestBuilder = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text(text);

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            final ChatPostMessageRequest request = requestBuilder.build();
            ChatPostMessageResponse response = executeWithRetry("chatPostMessage/error", () ->
                    client.chatPostMessage(request));
            return response.isOk();
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error sending error message", e);
            return false;
        }
    }

    /**
     * Send interactive buttons for clarification (e.g., date range selection).
     */
    public String sendClarificationButtons(String botToken, String channelId, String threadTs,
                                           String promptText, List<SlackButton> buttons) {
        try {
            MethodsClient client = slack.methods(botToken);

            // Build button blocks
            StringBuilder sb = new StringBuilder();
            sb.append(promptText).append("\n\n");
            for (int i = 0; i < buttons.size(); i++) {
                sb.append("`").append(i + 1).append("` ").append(buttons.get(i).label()).append("\n");
            }
            sb.append("\nReply with a number to choose (e.g. 1)");

            ChatPostMessageRequest.ChatPostMessageRequestBuilder requestBuilder = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text(sb.toString());

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            ChatPostMessageResponse response = client.chatPostMessage(requestBuilder.build());

            if (response.isOk()) {
                return response.getTs();
            }
            return null;
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error sending clarification buttons", e);
            return null;
        }
    }

    /**
     * Send date range question when query doesn't include date range.
     * 
     * @param llmGeneratedMessage LLM-generated message in user's language (preferred), or null to use fallback
     */
    public boolean sendDateRangeQuestion(String botToken, String channelId, String threadTs, 
                                         String originalQuery, String llmGeneratedMessage) {
        try {
            MethodsClient client = slack.methods(botToken);

            // Use LLM-generated message if available, otherwise use fallback
            String text;
            if (llmGeneratedMessage != null && !llmGeneratedMessage.isEmpty()) {
                text = llmGeneratedMessage;
            } else {
                // Fallback message (English)
                text = """
                    📅 *Please specify a date range*
                    
                    Your question doesn't include a date range.
                    
                    Examples:
                    • "last 7 days %s"
                    • "this month %s"
                    • "from 2024-01-01 to 2024-01-31 %s"
                    
                    Please try again with a date! 🙏
                    """.formatted(originalQuery, originalQuery, originalQuery);
            }

            ChatPostMessageRequest.ChatPostMessageRequestBuilder requestBuilder = ChatPostMessageRequest.builder()
                    .channel(channelId)
                    .text(text);

            if (threadTs != null && !threadTs.isEmpty()) {
                requestBuilder.threadTs(threadTs);
            }

            final ChatPostMessageRequest request = requestBuilder.build();
            ChatPostMessageResponse response = executeWithRetry("chatPostMessage/dateRange", () ->
                    client.chatPostMessage(request));

            if (response.isOk()) {
                log.info("[Slack] Date range question sent to channel: {}", channelId);
                return true;
            } else {
                log.error("[Slack] Failed to send date range question: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error sending date range question", e);
            return false;
        }
    }

    /**
     * Test Slack connection with the provided token.
     */
    public boolean testConnection(String botToken) {
        try {
            MethodsClient client = slack.methods(botToken);
            var response = client.authTest(r -> r);
            if (response.isOk()) {
                log.info("[Slack] Connection test successful. Bot: {}", response.getUser());
                return true;
            } else {
                log.error("[Slack] Connection test failed: {}", response.getError());
                return false;
            }
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error testing connection", e);
            return false;
        }
    }

    /**
     * Get conversation history for a thread.
     * Returns the last N messages in the thread formatted as a string.
     * 
     * @param botToken Bot token
     * @param channelId Channel ID
     * @param threadTs Thread timestamp
     * @param maxMessages Maximum number of messages to retrieve (default: 10)
     * @return Formatted conversation history string, or null if failed
     */
    public String getThreadHistory(String botToken, String channelId, String threadTs, int maxMessages) {
        try {
            MethodsClient client = slack.methods(botToken);
            
            // Get thread replies
            var response = client.conversationsReplies(r -> r
                    .channel(channelId)
                    .ts(threadTs)
                    .limit(maxMessages)
            );
            
            if (!response.isOk()) {
                log.warn("[Slack] Failed to get thread history: {}", response.getError());
                return null;
            }
            
            var messages = response.getMessages();
            if (messages == null || messages.isEmpty()) {
                return null;
            }
            
            // Format messages into a conversation history string
            StringBuilder history = new StringBuilder();
            for (var message : messages) {
                // Skip the current message (last one)
                if (message.getTs().equals(threadTs) && messages.size() > 1) {
                    continue;
                }
                
                String role = message.getBotId() != null ? "Inquery" : "User";
                String text = message.getText();
                
                // Truncate very long messages (like CSV data)
                if (text != null && text.length() > 2000) {
                    text = text.substring(0, 2000) + "...[truncated]";
                }
                
                history.append(role).append(": ").append(text).append("\n\n");
            }
            
            String result = history.toString().trim();
            log.info("[Slack] Retrieved thread history: {} messages, {} chars", messages.size(), result.length());
            return result.isEmpty() ? null : result;
            
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error getting thread history", e);
            return null;
        }
    }

    /**
     * Simple button data class.
     */
    public record SlackButton(String label, String value) {}

    /**
     * User info data class.
     */
    public record SlackUserInfo(String userId, String realName, String displayName, String email) {}

    /**
     * Get user information from Slack.
     * 
     * @param botToken Bot token
     * @param userId User ID (e.g., "U12345678")
     * @return SlackUserInfo with user details, or null if failed
     */
    public SlackUserInfo getUserInfo(String botToken, String userId) {
        try {
            MethodsClient client = slack.methods(botToken);
            var response = client.usersInfo(r -> r.user(userId));
            
            if (!response.isOk()) {
                log.warn("[Slack] Failed to get user info for {}: {}", userId, response.getError());
                return null;
            }
            
            var user = response.getUser();
            if (user == null) {
                return null;
            }
            
            String realName = user.getRealName();
            String displayName = user.getProfile() != null ? user.getProfile().getDisplayName() : null;
            String email = user.getProfile() != null ? user.getProfile().getEmail() : null;
            
            // Use display name if available, otherwise fall back to real name
            if (displayName == null || displayName.isEmpty()) {
                displayName = realName;
            }
            
            log.debug("[Slack] Got user info - id: {}, realName: {}, displayName: {}", 
                    userId, realName, displayName);
            
            return new SlackUserInfo(userId, realName, displayName, email);
            
        } catch (IOException | SlackApiException e) {
            log.error("[Slack] Error getting user info for {}", userId, e);
            return null;
        }
    }
}
