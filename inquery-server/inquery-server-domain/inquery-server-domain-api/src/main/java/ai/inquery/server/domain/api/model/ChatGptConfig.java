package ai.inquery.server.domain.api.model;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import lombok.Data;

/**
 * @version ChatGptConfig.java, v 0.1 May 9, 2023 13:47 moji Exp $
 */
@Data
public class ChatGptConfig {
    /**
     * inquery APIKEY
     */
    private String inqueryApiKey;

    /**
     * inquery APIHOST
     */
    private String inqueryApiHost;

    /**
     * OpenAi APIKEY
     */
    private String apiKey;

    /**
     * OpenAi APIHOST
     */
    private String apiHost;

    /**
     * HTTP proxy host
     */
    private String httpProxyHost;

    /**
     * HTTP proxy Port
     */
    private String httpProxyPort;

    /**
     * AI type
     * @see AiSqlSourceEnum
     */
    private String aiSqlSource;

    /**
     * Custom AI interface
     */
    private String restAiUrl;

    /**
     * Whether the Rest interface streams output
     * Optional, default value is TRUE
     */
    private Boolean restAiStream = Boolean.TRUE;

    /**
     * Get Azure OpenAI key credential from the Azure Portal
     */
    private String azureApiKey;

    /**
     * Get Azure OpenAI endpoint from the Azure Portal
     */
    private String azureEndpoint;

    /**
     * deploymentId of the deployed model, default gpt-3.5-turbo
     */
    private String azureDeploymentId;
}
