
package ai.inquery.server.web.api.controller.ai.inquery.client;

import ai.inquery.server.domain.api.model.Config;
import ai.inquery.server.domain.api.service.ConfigService;
import ai.inquery.server.web.api.util.ApplicationContextUtil;
import com.unfbx.chatgpt.constant.OpenAIConst;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 */
@Slf4j
public class InqueryAIClient {

    public static final String INQUERY_OPENAI_KEY = "inquery.apiKey";

    /**
     * OPENAI interface domain name
     */
    public static final String INQUERY_OPENAI_HOST = "inquery.apiHost";

    /**
     * OPENAI model
     */
    public static final String INQUERY_OPENAI_MODEL = "inquery.model";

    /**
     * FASTCHAT OPENAI embedding model
     */
    public static final String INQUERY_EMBEDDING_MODEL= "fastchat.embedding.model";


    private static volatile InqueryAIStreamClient INQUERY_AI_STREAM_CLIENT;

    public static InqueryAIStreamClient getInstance() {
        if (INQUERY_AI_STREAM_CLIENT != null) {
            return INQUERY_AI_STREAM_CLIENT;
        } else {
            return singleton();
        }
    }

    private static InqueryAIStreamClient singleton() {
        if (INQUERY_AI_STREAM_CLIENT == null) {
            synchronized (InqueryAIClient.class) {
                if (INQUERY_AI_STREAM_CLIENT == null) {
                    refresh();
                }
            }
        }
        return INQUERY_AI_STREAM_CLIENT;
    }

    public static void refresh() {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);

        INQUERY_AI_STREAM_CLIENT = InqueryAIStreamClient.builder().apiHost(getApiHost(configService))
                .apiKey(getApiKey(configService)).model(getModel(configService)).build();
    }

    private static String getApiHost(ConfigService configService) {
        Config apiHostConfig = configService.find(INQUERY_OPENAI_HOST).getData();

        if (Objects.nonNull(apiHostConfig)) {
            return apiHostConfig.getContent();
        }

        String apiHost = ApplicationContextUtil.getProperty(INQUERY_OPENAI_HOST);

        if (apiHost.isBlank()) {
            return OpenAIConst.OPENAI_HOST;
        }

        return apiHost;
    }

    private static String getApiKey(ConfigService configService) {
        String apiKey;

        Config config = configService.find(INQUERY_OPENAI_KEY).getData();

        if (Objects.nonNull(config)) {
            apiKey = config.getContent();
        } else {
            apiKey = ApplicationContextUtil.getProperty(INQUERY_OPENAI_KEY);
        }

        log.info("refresh inquery apikey:{}", maskApiKey(apiKey));

        return apiKey;
    }

    private static String getModel(ConfigService configService) {
        Config modelConfig = configService.find(INQUERY_OPENAI_MODEL).getData();

        if (Objects.nonNull(modelConfig)) {
            return modelConfig.getContent();
        }

        return null;
    }

    private static String maskApiKey(String input) {
        if (Objects.isNull(input)) {
            return null;
        }

        StringBuilder maskedString = new StringBuilder(input);
        for (int i = input.length() / 4; i < input.length() / 2; i++) {
            maskedString.setCharAt(i, '*');
        }
        return maskedString.toString();
    }
}
