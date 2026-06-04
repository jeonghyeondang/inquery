package ai.inquery.server.web.api.controller.ai.claude.listener;

import com.anthropic.models.messages.RawMessageStreamEvent;
import com.unfbx.chatgpt.entity.chat.Message;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ClaudeAIEventSourceListener for Anthropic SDK
 */
@Slf4j
public class ClaudeAIEventSourceListener {

    private SseEmitter sseEmitter;
    private StringBuilder fullResponseBuilder = new StringBuilder();

    public ClaudeAIEventSourceListener(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
    }

    /**
     * Get the full response text accumulated during streaming
     */
    public String getFullResponse() {
        return fullResponseBuilder.toString();
    }

    /**
     * Handle Anthropic SDK stream events
     */
    @SneakyThrows
    public void handleAnthropicEvent(RawMessageStreamEvent event) {
        log.debug("Claude AI event type: {}", event.getClass().getSimpleName());

        // Handle messageStart
        event.messageStart().ifPresent(msgStart -> {
            log.info("Claude AI message start");
        });
        
        // Handle contentBlockDelta - this returns a Stream
        event.contentBlockDelta().stream().forEach(delta -> {
            delta.delta().text().ifPresent(textDelta -> {
                String text = textDelta.text();
                if (text != null && !text.isEmpty()) {
                    try {
                        // Accumulate full response for context caching
                        fullResponseBuilder.append(text);

                        Message message = new Message();
                        message.setContent(text);
                        sseEmitter.send(SseEmitter.event()
                                .id(null)
                                .data(message)
                                .reconnectTime(3000));
                    } catch (Exception e) {
                        log.error("Error sending content delta", e);
                    }
                }
            });
        });
        
        // Handle messageStop
        event.messageStop().ifPresent(msgStop -> {
            log.info("Claude AI message stop");
            try {
                sseEmitter.send(SseEmitter.event()
                        .id("[DONE]")
                        .data("[DONE]")
                        .reconnectTime(3000));
                sseEmitter.complete();
            } catch (Exception e) {
                log.error("Error sending completion", e);
            }
        });
        
        // Handle other event types if needed
        event.messageDelta().ifPresent(delta -> {
            // Handle message delta if needed
        });
        
        event.contentBlockStart().ifPresent(start -> {
            // Handle content block start if needed
        });
        
        event.contentBlockStop().ifPresent(stop -> {
            // Handle content block stop if needed
        });
    }

    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        try {
            String errorMessage = t != null ? t.getMessage() : "Unknown error";
            log.error("Claude AI error: {}", errorMessage, t);
            
            Message message = new Message();
            message.setContent("Claude AI error: " + errorMessage);
            sseEmitter.send(SseEmitter.event()
                    .id("[ERROR]")
                    .data(message));
            sseEmitter.send(SseEmitter.event()
                    .id("[DONE]")
                    .data("[DONE]"));
            sseEmitter.complete();
        } catch (Exception exception) {
            log.error("Exception in sending error data:", exception);
        }
    }
}
