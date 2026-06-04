package ai.inquery.server.web.api.controller.ai.inquery.client;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.web.api.controller.ai.inquery.interceptor.InqueryHeaderAuthorizationInterceptor;
import ai.inquery.server.web.api.controller.ai.fastchat.client.FastChatOpenAiApi;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbedding;
import ai.inquery.server.web.api.controller.ai.fastchat.embeddings.FastChatEmbeddingResponse;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import cn.hutool.http.ContentType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfbx.chatgpt.entity.chat.ChatCompletion;
import com.unfbx.chatgpt.entity.chat.Message;
import com.unfbx.chatgpt.interceptor.HeaderAuthorizationInterceptor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Fast Chat Aligned Client
 *
 */
@Slf4j
public class InqueryAIStreamClient {

    /**
     * apikey
     */
    @Getter
    @NotNull
    private String apiKey;

    /**
     * apiHost
     */
    @Getter
    @NotNull
    private String apiHost;

    /**
     * model
     */
    @Getter
    private String model;

    /**
     * embeddingModel
     */
    @Getter
    private String embeddingModel;

    /**
     * okHttpClient
     */
    @Getter
    private OkHttpClient okHttpClient;

    @Getter
    private FastChatOpenAiApi fastChatOpenAiApi;

    /**
     * @param builder
     */
    private InqueryAIStreamClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiHost = builder.apiHost;
        if (!apiHost.endsWith("/")){
            apiHost = apiHost + "/";
        }
        this.model = builder.model;
        this.embeddingModel = builder.embeddingModel;
        if (Objects.isNull(builder.okHttpClient)) {
            builder.okHttpClient = this.okHttpClient();
        }
        okHttpClient = builder.okHttpClient;
        this.fastChatOpenAiApi = new Retrofit.Builder()
                .baseUrl(apiHost)
                .client(okHttpClient)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create())
                .build().create(FastChatOpenAiApi.class);
    }

    /**
     * okhttpclient
     */
    private OkHttpClient okHttpClient() {
        OkHttpClient okHttpClient = new OkHttpClient
            .Builder()
            .addInterceptor(new InqueryHeaderAuthorizationInterceptor(this.apiKey, this.model))
            .connectTimeout(50, TimeUnit.SECONDS)
            .writeTimeout(50, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .build();
        return okHttpClient;
    }

    /**
     * structure
     *
     * @return
     */
    public static InqueryAIStreamClient.Builder builder() {
        return new InqueryAIStreamClient.Builder();
    }

    /**
     * builder
     */
    public static final class Builder {
        private String apiKey;

        private String apiHost;

        private String model;

        private String embeddingModel;

        /**
         * OkhttpClient
         */
        private OkHttpClient okHttpClient;

        public Builder() {
        }

        public InqueryAIStreamClient.Builder apiKey(String apiKeyValue) {
            this.apiKey = apiKeyValue;
            return this;
        }

        /**
         * @param apiHostValue
         * @return
         */
        public InqueryAIStreamClient.Builder apiHost(String apiHostValue) {
            this.apiHost = apiHostValue;
            return this;
        }

        /**
         * @param modelValue
         * @return
         */
        public InqueryAIStreamClient.Builder model(String modelValue) {
            this.model = modelValue;
            return this;
        }

        public InqueryAIStreamClient.Builder embeddingModel(String embeddingModelValue) {
            this.embeddingModel = embeddingModelValue;
            return this;
        }

        public InqueryAIStreamClient.Builder okHttpClient(OkHttpClient val) {
            this.okHttpClient = val;
            return this;
        }

        public InqueryAIStreamClient build() {
            return new InqueryAIStreamClient(this);
        }

    }

    /**
     * Q&A interface stream form
     *
     * @param chatMessages
     * @param eventSourceListener
     */
    public void streamCompletions(List<Message> chatMessages, EventSourceListener eventSourceListener) {
        if (CollectionUtils.isEmpty(chatMessages)) {
            log.error("param error:Chat Prompt cannot be empty");
            throw new ParamBusinessException("prompt");
        }
        if (Objects.isNull(eventSourceListener)) {
            log.error("param error: ChatEventSourceListener cannot be empty");
            throw new ParamBusinessException();
        }
        try {
            ChatCompletion chatCompletion = ChatCompletion.builder()
                    .messages(chatMessages)
                    .stream(true)
                    .build();

            EventSource.Factory factory = EventSources.createFactory(this.okHttpClient);
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String requestBody = mapper.writeValueAsString(chatCompletion);
            Request request = new Request.Builder()
                    .url(this.apiHost + "v1/chat/completions")
                    .post(RequestBody.create(MediaType.parse(ContentType.JSON.getValue()), requestBody))
                    .build();
            //Create event
            EventSource eventSource = factory.newEventSource(request, eventSourceListener);
            log.info("finish invoking chat ai");
        } catch (Exception e) {
            log.error("chat ai error", e);
            eventSourceListener.onFailure(null, e, null);
            throw new ParamBusinessException();
        }
    }

    /**
     * Creates an embedding vector representing the input text.
     *
     * @param input
     * @return EmbeddingResponse
     */
    public FastChatEmbeddingResponse embeddings(String input) {
        FastChatEmbedding embedding = FastChatEmbedding.builder().input(input).build();
        if (StringUtils.isNotBlank(this.embeddingModel)) {
            embedding.setModel(this.embeddingModel);
        }
        return this.embeddings(embedding);
    }

    /**
     * Creates an embedding vector representing the input text.
     *
     * @param embedding
     * @return EmbeddingResponse
     */
    public FastChatEmbeddingResponse embeddings(FastChatEmbedding embedding) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String requestBody = mapper.writeValueAsString(embedding);
            Request request = new Request.Builder()
                    .url(this.apiHost + "v1/embeddings")
                    .post(RequestBody.create(MediaType.parse(ContentType.JSON.getValue()), requestBody))
                    .build();

            FastChatEmbeddingResponse chatEmbeddingResponse = null;
            Response response = this.okHttpClient.newCall(request).execute();
            if (response.isSuccessful()) {
                String body = response.body().string();
                chatEmbeddingResponse = mapper.readValue(body, FastChatEmbeddingResponse.class);
            }
            log.info("finish invoking chat embedding");
            return chatEmbeddingResponse;
        } catch (Exception e) {
            log.error("chat ai error", e);
            throw new ParamBusinessException();
        }
    }
}
