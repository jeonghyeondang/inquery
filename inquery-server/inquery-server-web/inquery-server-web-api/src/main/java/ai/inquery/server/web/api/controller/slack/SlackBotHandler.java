package ai.inquery.server.web.api.controller.slack;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.AssistantThreadStartedEvent;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack Bot Handler using Socket Mode.
 * Listens for app_mention and direct message events.
 * 
 * Configuration keys (stored in config table):
 * - SLACK_BOT_TOKEN: Bot User OAuth Token (xoxb-...)
 * - SLACK_APP_TOKEN: App-Level Token (xapp-...)
 * - SLACK_ENABLED: "true" to enable Slack integration
 * - SLACK_DEFAULT_DATASOURCE_ID: Default database connection ID
 */
@Slf4j
@Component
public class SlackBotHandler {

    public static final String CONFIG_SLACK_BOT_TOKEN = "SLACK_BOT_TOKEN";
    public static final String CONFIG_SLACK_APP_TOKEN = "SLACK_APP_TOKEN";
    public static final String CONFIG_SLACK_ENABLED = "SLACK_ENABLED";
    public static final String CONFIG_SLACK_DEFAULT_DATASOURCE_ID = "SLACK_DEFAULT_DATASOURCE_ID";
    public static final String CONFIG_SLACK_DEFAULT_DATABASE = "SLACK_DEFAULT_DATABASE";
    public static final String CONFIG_SLACK_DEFAULT_SCHEMA = "SLACK_DEFAULT_SCHEMA";
    public static final String CONFIG_SLACK_DEFAULT_MODEL = "SLACK_DEFAULT_MODEL";

    @Autowired
    private ConfigService configService;

    @Autowired
    private SlackChatService slackChatService;

    @Autowired
    private ai.inquery.server.domain.core.query.SchemaSearcher schemaSearcher;

    private SocketModeApp socketModeApp;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Pattern to remove bot mention from message
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@[A-Z0-9]+>\\s*");

    // Executor for async processing (allows immediate ack to Slack)
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    // Cache to prevent duplicate message processing (Slack retries if no ack within 3 seconds)
    private final ConcurrentHashMap<String, Long> processedMessages = new ConcurrentHashMap<>();
    private static final long MESSAGE_CACHE_TTL_MS = 60_000; // 1 minute

    @PostConstruct
    public void init() {
        // Try to start if enabled
        startIfEnabled();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    /**
     * Start Slack bot if enabled in configuration.
     * Note: Sets DB session explicitly for @PostConstruct context where ThreadLocal may not be initialized.
     */
    public synchronized void startIfEnabled() {
        if (isRunning.get()) {
            log.info("[SlackBot] Already running");
            return;
        }

        // Ensure DB session is available (ThreadLocal may not be set in @PostConstruct)
        boolean sessionCreated = false;
        if (!Dbutils.hasSession()) {
            Dbutils.setSession();
            sessionCreated = true;
        }

        try {
            // Check if enabled
            String enabled = getConfigValue(CONFIG_SLACK_ENABLED);
            if (!"true".equalsIgnoreCase(enabled)) {
                log.info("[SlackBot] Slack integration is disabled");
                return;
            }

            String botToken = getConfigValue(CONFIG_SLACK_BOT_TOKEN);
            String appToken = getConfigValue(CONFIG_SLACK_APP_TOKEN);

            if (StringUtils.isBlank(botToken) || StringUtils.isBlank(appToken)) {
                log.warn("[SlackBot] Bot token or App token not configured");
                return;
            }

            start(botToken, appToken);
        } catch (Exception e) {
            log.error("[SlackBot] Failed to start", e);
        } finally {
            if (sessionCreated) {
                Dbutils.removeSession();
            }
        }
    }

    /**
     * Start Slack bot with provided tokens.
     */
    public synchronized void start(String botToken, String appToken) throws Exception {
        if (isRunning.get()) {
            log.info("[SlackBot] Already running, stopping first...");
            stop();
        }

        log.info("[SlackBot] Starting Socket Mode connection...");
        log.info("[SlackBot] Tokens are configured");

        // Create Bolt App
        AppConfig config = AppConfig.builder()
                .singleTeamBotToken(botToken)
                .build();

        App app = new App(config);

        // Register event handlers
        registerEventHandlers(app, botToken);

        // Create and start Socket Mode App
        socketModeApp = new SocketModeApp(appToken, app);
        socketModeApp.startAsync();

        isRunning.set(true);
        log.info("[SlackBot] Socket Mode connection started successfully");
        log.info("[SlackBot] Waiting for events (app_mention, message.im)...");

        // Warmup connections in background (reduces first query latency)
        executor.submit(() -> {
            // 1. Pinecone warmup
            try {
                log.info("[SlackBot] Starting Pinecone warmup...");
                schemaSearcher.warmUp();
                log.info("[SlackBot] Pinecone warmup completed");
            } catch (Exception e) {
                log.warn("[SlackBot] Pinecone warmup failed (non-fatal): {}", e.getMessage());
            }

            // 2. Snowflake connection warmup
            Dbutils.setSession();
            try {
                Long dataSourceId = getDefaultDataSourceId();
                if (dataSourceId != null) {
                    log.info("[SlackBot] Starting Snowflake connection warmup for dataSourceId: {}", dataSourceId);
                    slackChatService.warmupConnection(dataSourceId);
                    log.info("[SlackBot] Snowflake connection warmup completed");
                }
            } catch (Exception e) {
                log.warn("[SlackBot] Snowflake warmup failed (non-fatal): {}", e.getMessage());
            } finally {
                Dbutils.removeSession();
            }
        });
    }

    /**
     * Stop Slack bot.
     */
    public synchronized void stop() {
        if (!isRunning.get()) {
            return;
        }

        try {
            if (socketModeApp != null) {
                socketModeApp.stop();
                socketModeApp = null;
            }
            isRunning.set(false);
            log.info("[SlackBot] Stopped");
        } catch (Exception e) {
            log.error("[SlackBot] Error stopping", e);
        }
    }

    /**
     * Restart Slack bot (used when configuration changes).
     */
    public void restart() {
        stop();
        startIfEnabled();
    }

    /**
     * Check if bot is running.
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Register event handlers for Slack events.
     */
    private void registerEventHandlers(App app, String botToken) {
        // Handle @mention in channels
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            AppMentionEvent event = payload.getEvent();
            String messageTs = event.getTs();
            
            // Deduplicate: check if already processing this message
            if (isMessageAlreadyProcessed(messageTs)) {
                log.debug("[SlackBot] Duplicate mention event ignored: {}", messageTs);
                return ctx.ack();
            }
            
            log.info("[SlackBot] ========== APP MENTION EVENT RECEIVED ==========");
            String channelId = event.getChannel();
            String userId = event.getUser();
            String text = event.getText();
            String threadTs = event.getThreadTs() != null ? event.getThreadTs() : messageTs;
            String cleanText = removeMention(text);

            log.info("[SlackBot] App mention - channel: {}, user: {}, text: {}", 
                    channelId, userId, cleanText);

            // Get config values BEFORE ack (need DB session in current thread)
            Dbutils.setSession();
            Long dataSourceId;
            String databaseName;
            String schemaName;
            String modelName;
            try {
                dataSourceId = getDefaultDataSourceId();
                databaseName = getConfigValue(CONFIG_SLACK_DEFAULT_DATABASE);
                schemaName = getConfigValue(CONFIG_SLACK_DEFAULT_SCHEMA);
                modelName = getConfigValue(CONFIG_SLACK_DEFAULT_MODEL);
            } finally {
                Dbutils.removeSession();
            }

            // Ack immediately to prevent Slack retry (must respond within 3 seconds)
            ctx.ack();

            // Process asynchronously with executor (not ForkJoinPool)
            // messageTs = current message timestamp (for reactions)
            // threadTs = thread parent timestamp (for replies)
            final String userMsgTs = messageTs;
            executor.submit(() -> {
                Dbutils.setSession();
                try {
                    log.info("[SlackBot] Processing mention in thread: {}", Thread.currentThread().getName());
                    slackChatService.processQuery(
                            botToken, channelId, threadTs, userMsgTs, userId, cleanText,
                            dataSourceId, databaseName, schemaName, modelName
                    );
                } catch (Exception e) {
                    log.error("[SlackBot] Error processing mention query", e);
                } finally {
                    Dbutils.removeSession();
                }
            });

            return ctx.ack(); // Return ack (already sent above, but required by interface)
        });

        // Handle direct messages - using MessageEvent for message.im
        app.event(MessageEvent.class, (payload, ctx) -> {
            MessageEvent event = payload.getEvent();
            String messageTs = event.getTs();
            
            // Ignore bot messages and message subtypes (edits, etc.)
            if (event.getBotId() != null || event.getSubtype() != null) {
                return ctx.ack();
            }

            // Check channel type for DM (im = direct message)
            String channelType = event.getChannelType();
            String channelId = event.getChannel();
            boolean isDM = "im".equals(channelType) || (channelId != null && channelId.startsWith("D"));
            
            if (!isDM) {
                return ctx.ack();
            }
            
            // Deduplicate: check if already processing this message
            if (isMessageAlreadyProcessed(messageTs)) {
                log.debug("[SlackBot] Duplicate DM event ignored: {}", messageTs);
                return ctx.ack();
            }

            log.info("[SlackBot] ========== DM EVENT RECEIVED ==========");
            String userId = event.getUser();
            String text = event.getText();
            String threadTs = event.getThreadTs() != null ? event.getThreadTs() : messageTs;

            log.info("[SlackBot] DM received - channel: {}, user: {}, text: {}", 
                    channelId, userId, text);

            // Get config values BEFORE ack (need DB session in current thread)
            Dbutils.setSession();
            Long dataSourceId;
            String databaseName;
            String schemaName;
            String modelName;
            try {
                dataSourceId = getDefaultDataSourceId();
                databaseName = getConfigValue(CONFIG_SLACK_DEFAULT_DATABASE);
                schemaName = getConfigValue(CONFIG_SLACK_DEFAULT_SCHEMA);
                modelName = getConfigValue(CONFIG_SLACK_DEFAULT_MODEL);
            } finally {
                Dbutils.removeSession();
            }

            // Ack immediately to prevent Slack retry (must respond within 3 seconds)
            ctx.ack();

            // Process asynchronously with executor (not ForkJoinPool)
            // messageTs = current message timestamp (for reactions)
            // threadTs = thread parent timestamp (for replies)
            final String userMsgTs = messageTs;
            executor.submit(() -> {
                Dbutils.setSession();
                try {
                    log.info("[SlackBot] Processing DM in thread: {}", Thread.currentThread().getName());
                    slackChatService.processQuery(
                            botToken, channelId, threadTs, userMsgTs, userId, text,
                            dataSourceId, databaseName, schemaName, modelName
                    );
                } catch (Exception e) {
                    log.error("[SlackBot] Error processing DM query", e);
                } finally {
                    Dbutils.removeSession();
                }
            });

            return ctx.ack(); // Return ack (already sent above, but required by interface)
        });

        // Handle message_changed events (when bot updates its own messages)
        // This prevents "no handler found" warnings
        app.event(MessageChangedEvent.class, (payload, ctx) -> {
            // Just acknowledge - no action needed for message edits
            return ctx.ack();
        });

        // Handle message_deleted events (when bot deletes its thinking messages)
        // This prevents "no handler found" warnings
        app.event(MessageDeletedEvent.class, (payload, ctx) -> {
            return ctx.ack();
        });

        app.event(AssistantThreadStartedEvent.class, (payload, ctx) -> {
            return ctx.ack();
        });

        log.info("[SlackBot] Event handlers registered for: AppMentionEvent, MessageEvent, MessageChangedEvent, MessageDeletedEvent, AssistantThreadStartedEvent");
    }

    /**
     * Remove bot mention from message text.
     */
    private String removeMention(String text) {
        if (text == null) return "";
        Matcher matcher = MENTION_PATTERN.matcher(text);
        return matcher.replaceAll("").trim();
    }

    /**
     * Get configuration value from database.
     */
    private String getConfigValue(String code) {
        try {
            DataResult<Config> result = configService.find(code);
            if (result != null && result.getData() != null) {
                return result.getData().getContent();
            }
        } catch (Exception e) {
            log.warn("[SlackBot] Failed to get config: {}", code);
        }
        return null;
    }

    /**
     * Get default data source ID from configuration.
     */
    private Long getDefaultDataSourceId() {
        String value = getConfigValue(CONFIG_SLACK_DEFAULT_DATASOURCE_ID);
        if (StringUtils.isNotBlank(value)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("[SlackBot] Invalid data source ID: {}", value);
            }
        }
        return null;
    }

    /**
     * Check if message was already processed (deduplication).
     * Slack may retry if ack is not received within 3 seconds.
     * Returns true if message is a duplicate (already being processed).
     */
    private boolean isMessageAlreadyProcessed(String messageTs) {
        if (messageTs == null) return false;
        
        long now = System.currentTimeMillis();
        
        // Clean up old entries
        processedMessages.entrySet().removeIf(entry -> 
                now - entry.getValue() > MESSAGE_CACHE_TTL_MS);
        
        // Try to add this message to cache
        Long previous = processedMessages.putIfAbsent(messageTs, now);
        
        // If previous is not null, message was already in cache (duplicate)
        return previous != null;
    }
}
