package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.core.impl.OutlookSearchResult;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.mapper.UserAIConfigMapper;
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
 * Outlook search service
 * Adapted from data-category project
 */
public class OutlookService extends AbstractSearchService {

    private final UserAIConfigDO userConfig;
    private final WebClient tokenWebClient;

    public OutlookService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl("https://graph.microsoft.com/v1.0")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build());
        this.userConfig = userConfig;
        this.tokenWebClient = WebClient.builder()
            .baseUrl("https://login.microsoftonline.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build();
    }

    public OutlookService(UserAIConfigSaveParam config) {
        this(toDO(config));
    }

    private static UserAIConfigDO toDO(UserAIConfigSaveParam config) {
        UserAIConfigDO d = new UserAIConfigDO();
        if (config == null) {
            return d;
        }
        d.setOutlookTenantId(config.getOutlookTenantId());
        d.setOutlookClientId(config.getOutlookClientId());
        d.setOutlookClientSecret(config.getOutlookClientSecret());
        d.setOutlookAccessToken(config.getOutlookAccessToken());
        d.setOutlookRefreshToken(config.getOutlookRefreshToken());
        d.setOutlookExpiresAt(config.getOutlookExpiresAt());
        d.setOutlookUserEmail(config.getOutlookUserEmail());
        return d;
    }

    public boolean isConfigured() {
        return canRefreshToken();
    }

    public List<OutlookSearchResult> searchEmails(String tableName, int maxResults) {
        List<OutlookSearchResult> results = new ArrayList<>();

        // Auto-refresh only mode: require refresh-token based config
        if (!canRefreshToken()) {
            logger.warn("Outlook auto-refresh config is not configured (refreshToken/tenantId/clientId required).");
            return results;
        }

        try {
            String searchQuery = extractTableName(tableName);
            String accessToken = ensureValidAccessToken(userConfig.getOutlookAccessToken() == null || userConfig.getOutlookAccessToken().isEmpty());
            if (accessToken == null || accessToken.isEmpty()) {
                logger.warn("Outlook access token is not available after refresh attempt.");
                return results;
            }
            logger.info("Outlook email search started: {} (query: {})", tableName, searchQuery);
            try {
                results = searchEmailsWithGraphAPI(accessToken, searchQuery, maxResults);
            } catch (OutlookAuthException authEx) {
                // Token may be expired or revoked; try refresh once if possible
                if (canRefreshToken()) {
                    logger.warn("Outlook auth failure (HTTP {}), attempting token refresh and retry once.", authEx.getStatusCode());
                    String refreshed = ensureValidAccessToken(true);
                    results = searchEmailsWithGraphAPI(refreshed, searchQuery, maxResults);
                } else {
                    throw authEx;
                }
            }
            logger.info("Outlook email search completed: {} results", results.size());
        } catch (Exception e) {
            logger.error("Outlook email search failed: {}", e.getMessage());
        }

        return results;
    }

    private List<OutlookSearchResult> searchEmailsWithGraphAPI(String token, String searchQuery, int maxResults) {
        List<OutlookSearchResult> results = new ArrayList<>();

        try {
            String userEmailPath = "";
            if (userConfig.getOutlookUserEmail() != null && !userConfig.getOutlookUserEmail().isEmpty()) {
                userEmailPath = userConfig.getOutlookUserEmail();
            }

            logger.debug("Outlook search query: {}", searchQuery);

            String finalPath;
            if (!userEmailPath.isEmpty()) {
                finalPath = "/users/" + userEmailPath + "/messages";
            } else {
                finalPath = "/messages";
            }

            String response = webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(finalPath);
                    uriBuilder.queryParam("$search", "\"" + searchQuery + "\"");
                    // $search requires ConsistencyLevel eventual, and $count improves compatibility in some tenants
                    uriBuilder.queryParam("$count", "true");
                    uriBuilder.queryParam("$top", maxResults);
                    uriBuilder.queryParam("$select", "id,subject,body,from,receivedDateTime,webLink,hasAttachments");
                    return uriBuilder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("ConsistencyLevel", "eventual")
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), clientResponse -> {
                    int statusCode = clientResponse.statusCode().value();
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(errorBody -> {
                            logger.error("Outlook Graph API error (HTTP {}): {}", statusCode, errorBody);
                            if (statusCode == 401 || statusCode == 403) {
                                return Mono.error(new OutlookAuthException(statusCode, errorBody));
                            }
                            return Mono.error(new RuntimeException("Outlook Graph API failed: " + statusCode));
                        });
                })
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .onErrorResume(e -> {
                    if (e instanceof OutlookAuthException) {
                        return Mono.error(e);
                    }
                    logger.error("Outlook Graph API request failed: {}", e.getMessage());
                    return Mono.just("{\"value\":[]}");
                })
                .block();

            if (response != null && !response.isEmpty()) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode valueNode = root.path("value");

                if (valueNode.isArray()) {
                    for (JsonNode message : valueNode) {
                        String messageId = message.path("id").asText("");
                        String subject = message.path("subject").asText("");
                        String body = message.path("body").path("content").asText("");
                        String bodyContentType = message.path("body").path("contentType").asText("");

                        if ("html".equalsIgnoreCase(bodyContentType)) {
                            body = body.replaceAll("<[^>]+>", " ")
                                .replaceAll("\\s+", " ")
                                .trim();
                        }

                        if (body.length() > 2000) {
                            body = body.substring(0, 2000) + "...";
                        }

                        JsonNode fromNode = message.path("from");
                        String from = fromNode.path("emailAddress").path("address").asText("");
                        if (from.isEmpty()) {
                            from = fromNode.path("emailAddress").path("name").asText("");
                        }

                        String receivedDateTime = message.path("receivedDateTime").asText("");
                        String webLink = message.path("webLink").asText("");
                        boolean hasAttachments = message.path("hasAttachments").asBoolean(false);

                        OutlookSearchResult outlookResult = new OutlookSearchResult(
                            messageId, subject, body, from, "", receivedDateTime, webLink, hasAttachments
                        );
                        results.add(outlookResult);
                    }
                }
            }
        } catch (OutlookAuthException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Outlook email search error", e);
        }

        return results;
    }

    private boolean canRefreshToken() {
        return userConfig.getOutlookRefreshToken() != null && !userConfig.getOutlookRefreshToken().isEmpty()
            && userConfig.getOutlookTenantId() != null && !userConfig.getOutlookTenantId().isEmpty()
            && userConfig.getOutlookClientId() != null && !userConfig.getOutlookClientId().isEmpty();
    }

    private String ensureValidAccessToken(boolean forceRefresh) {
        if (!canRefreshToken()) {
            return userConfig.getOutlookAccessToken();
        }

        Long expiresAt = userConfig.getOutlookExpiresAt();
        long now = System.currentTimeMillis();
        boolean nearExpiry = expiresAt != null && (expiresAt - now) <= 120_000L; // 2 minutes

        if (!forceRefresh && !nearExpiry) {
            return userConfig.getOutlookAccessToken();
        }

        TokenRefreshResult refreshed = refreshAccessToken();
        if (refreshed == null || refreshed.accessToken == null || refreshed.accessToken.isEmpty()) {
            return userConfig.getOutlookAccessToken();
        }
        return refreshed.accessToken;
    }

    private TokenRefreshResult refreshAccessToken() {
        String tenantId = userConfig.getOutlookTenantId();
        String clientId = userConfig.getOutlookClientId();
        String refreshToken = userConfig.getOutlookRefreshToken();

        if (tenantId == null || tenantId.isEmpty() || clientId == null || clientId.isEmpty() || refreshToken == null || refreshToken.isEmpty()) {
            return null;
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            if (userConfig.getOutlookClientSecret() != null && !userConfig.getOutlookClientSecret().isEmpty()) {
                form.add("client_secret", userConfig.getOutlookClientSecret());
            }

            String json = tokenWebClient.post()
                .uri("/{tenantId}/oauth2/v2.0/token", tenantId)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

            if (json == null || json.isEmpty()) {
                logger.warn("Outlook token refresh returned empty response.");
                return null;
            }

            JsonNode root = objectMapper.readTree(json);
            String newAccessToken = root.path("access_token").asText("");
            String newRefreshToken = root.path("refresh_token").asText("");
            long expiresIn = root.path("expires_in").asLong(0);

            if (newAccessToken == null || newAccessToken.isEmpty()) {
                String error = root.path("error").asText("");
                String errorDesc = root.path("error_description").asText("");
                logger.warn("Outlook token refresh failed: {} {}", error, errorDesc);
                return null;
            }

            Long newExpiresAt = expiresIn > 0 ? (System.currentTimeMillis() + (expiresIn * 1000L) - 60_000L) : null;
            // Update in-memory first
            userConfig.setOutlookAccessToken(newAccessToken);
            if (newRefreshToken != null && !newRefreshToken.isEmpty()) {
                userConfig.setOutlookRefreshToken(newRefreshToken);
            }
            if (newExpiresAt != null) {
                userConfig.setOutlookExpiresAt(newExpiresAt);
            }

            // Persist to DB (safe for async threads)
            persistUpdatedTokens();

            return new TokenRefreshResult(newAccessToken, userConfig.getOutlookRefreshToken(), userConfig.getOutlookExpiresAt());
        } catch (Exception e) {
            logger.warn("Outlook token refresh failed: {}", e.getMessage());
            return null;
        }
    }

    private void persistUpdatedTokens() {
        // This method may run on async threads. Ensure DB session is available.
        boolean openedSession = false;
        try {
            if (!Dbutils.hasSession()) {
                Dbutils.setSession();
                openedSession = true;
            }
            UserAIConfigMapper mapper = Dbutils.getMapper(UserAIConfigMapper.class);
            if (mapper == null || userConfig.getId() == null) {
                return;
            }
            mapper.updateById(userConfig);
        } catch (Exception e) {
            logger.warn("Failed to persist refreshed Outlook tokens: {}", e.getMessage());
        } finally {
            if (openedSession) {
                try {
                    Dbutils.removeSession();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static class TokenRefreshResult {
        final String accessToken;
        final String refreshToken;
        final Long expiresAt;

        TokenRefreshResult(String accessToken, String refreshToken, Long expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
        }
    }

    private static class OutlookAuthException extends RuntimeException {
        private final int statusCode;

        OutlookAuthException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int getStatusCode() {
            return statusCode;
        }
    }
}







