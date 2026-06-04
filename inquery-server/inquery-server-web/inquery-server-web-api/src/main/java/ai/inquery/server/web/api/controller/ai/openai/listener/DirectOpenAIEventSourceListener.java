package ai.inquery.server.web.api.controller.ai.openai.listener;

import ai.inquery.server.web.api.controller.ai.response.ChatCompletionResponse;
import ai.inquery.server.web.api.controller.ai.response.ChatChoice;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfbx.chatgpt.entity.chat.Message;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Direct OpenAI API EventSourceListener
 * Handles streaming responses from OpenAI API
 * 
 */
@Slf4j
public class DirectOpenAIEventSourceListener extends EventSourceListener {

    private final SseEmitter sseEmitter;
    private final ObjectMapper objectMapper;

    public DirectOpenAIEventSourceListener(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void onOpen(EventSource eventSource, Response response) {
        log.info("OpenAI SSE connection established");
    }

    @SneakyThrows
    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
        log.debug("OpenAI returns data: {}", data);
        
        if (data.equals("[DONE]")) {
            log.info("OpenAI stream completed");
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]")
                .reconnectTime(3000));
            sseEmitter.complete();
            return;
        }
        
        // Skip "data: " prefix if present
        String jsonData = data;
        if (data.startsWith("data: ")) {
            jsonData = data.substring(6);
        }
        
        try {
            // Parse OpenAI response
            ChatCompletionResponse completionResponse = objectMapper.readValue(jsonData, ChatCompletionResponse.class);
            
            // Extract content from delta or text field
            String text = null;
            if (completionResponse.getChoices() != null && !completionResponse.getChoices().isEmpty()) {
                ChatChoice choice = completionResponse.getChoices().get(0);
                if (choice.getDelta() != null && choice.getDelta().getContent() != null) {
                    text = choice.getDelta().getContent();
                } else if (choice.getText() != null) {
                    text = choice.getText();
                }
            }
            
            if (text != null) {
                Message message = new Message();
                message.setContent(text);
                sseEmitter.send(SseEmitter.event()
                    .id(completionResponse.getId())
                    .data(message)
                    .reconnectTime(3000));
            }
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: {}", jsonData, e);
        }
    }

    @Override
    public void onClosed(EventSource eventSource) {
        log.info("OpenAI SSE connection closed");
        sseEmitter.complete();
    }

    @SneakyThrows
    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        log.error("OpenAI SSE connection failed", t);
        
        if (response != null) {
            log.error("Response code: {}, message: {}", response.code(), response.message());
            try {
                String body = response.body() != null ? response.body().string() : "No response body";
                log.error("Response body: {}", body);
            } catch (Exception e) {
                log.error("Failed to read response body", e);
            }
        }
        
        try {
            sseEmitter.send(SseEmitter.event()
                .data("[ERROR]")
                .reconnectTime(3000));
            sseEmitter.completeWithError(t);
        } catch (Exception e) {
            log.error("Failed to send error to client", e);
        }
    }
}

