package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Drive search service (BYO OAuth credentials).
 *
 * <p>Searches Drive for Google Docs / Sheets matching a keyword via the Drive v3
 * {@code files.list} endpoint, then exports the matched files as plain text / CSV
 * via {@code files.export}. Only the {@code drive.readonly} scope is required.
 *
 * <p>Access tokens are auto-refreshed in-memory using the stored refresh token
 * (Google refresh tokens are long-lived), mirroring {@link OutlookService}.
 */
public class GoogleDriveService extends AbstractSearchService {

    private static final String DOC_MIME = "application/vnd.google-apps.document";
    private static final String SHEET_MIME = "application/vnd.google-apps.spreadsheet";
    private static final int MAX_CONTENT_CHARS = 8000;
    private static final int MAX_SHEET_ROWS_PER_TAB = 50;

    private final UserAIConfigDO userConfig;
    private final WebClient tokenWebClient;
    private final WebClient sheetsWebClient;

    public GoogleDriveService(UserAIConfigDO userConfig) {
        super(WebClient.builder()
            .baseUrl("https://www.googleapis.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            // Drive export of large files can exceed the default 256KB buffer.
            .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
            .build());
        this.userConfig = userConfig;
        this.tokenWebClient = WebClient.builder()
            .baseUrl("https://oauth2.googleapis.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build();
        // Sheets API (read access covered by the drive.readonly scope) — used to read
        // every tab of a spreadsheet, not just the first one like Drive's CSV export.
        this.sheetsWebClient = WebClient.builder()
            .baseUrl("https://sheets.googleapis.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
            .build();
    }

    public GoogleDriveService(UserAIConfigSaveParam config) {
        this(toDO(config));
    }

    private static UserAIConfigDO toDO(UserAIConfigSaveParam config) {
        UserAIConfigDO d = new UserAIConfigDO();
        d.setGoogleClientId(config.getGoogleClientId());
        d.setGoogleClientSecret(config.getGoogleClientSecret());
        d.setGoogleAccessToken(config.getGoogleAccessToken());
        d.setGoogleRefreshToken(config.getGoogleRefreshToken());
        d.setGoogleExpiresAt(config.getGoogleExpiresAt());
        return d;
    }

    public boolean isConfigured() {
        return canRefreshToken();
    }

    /**
     * Search Drive for Google Docs / Sheets matching the keyword.
     * Content is left empty; call {@link #fetchContent} for the top hits.
     */
    public List<GoogleDriveSearchResult> searchFiles(String keyword, int maxResults) {
        List<GoogleDriveSearchResult> results = new ArrayList<>();
        if (!canRefreshToken()) {
            logger.warn("Google Drive is not configured (refreshToken/clientId/clientSecret required).");
            return results;
        }

        String accessToken = ensureValidAccessToken(false);
        if (accessToken == null || accessToken.isEmpty()) {
            logger.warn("Google access token is not available after refresh attempt.");
            return results;
        }

        try {
            String escaped = keyword == null ? "" : keyword.replace("\\", "\\\\").replace("'", "\\'");
            String q = "trashed = false"
                + " and (mimeType = '" + DOC_MIME + "' or mimeType = '" + SHEET_MIME + "')"
                + " and (name contains '" + escaped + "' or fullText contains '" + escaped + "')";

            JsonNode root = listFiles(accessToken, q, maxResults);
            if (root == null) {
                // Retry once after a forced refresh in case the token was revoked/expired.
                accessToken = ensureValidAccessToken(true);
                root = listFiles(accessToken, q, maxResults);
            }
            if (root == null) {
                return results;
            }

            for (JsonNode file : root.path("files")) {
                String id = file.path("id").asText("");
                String name = file.path("name").asText("");
                String mime = file.path("mimeType").asText("");
                String url = file.path("webViewLink").asText("");
                if (!id.isEmpty()) {
                    results.add(new GoogleDriveSearchResult(id, name, mime, url, ""));
                }
            }
            logger.info("Google Drive search completed: {} result(s) for '{}'", results.size(), keyword);
        } catch (Exception e) {
            logger.error("Google Drive search failed for keyword '{}': {}", keyword, e.getMessage());
        }
        return results;
    }

    /**
     * Fetch a file's text content.
     *
     * <p>Docs are exported as plain text via Drive. Spreadsheets are read tab-by-tab
     * through the Sheets API (every sheet, not just the first one) so downstream
     * consumers like AI metadata collection see the full document.
     */
    public String fetchContent(String fileId, String mimeType) {
        if (!canRefreshToken()) {
            return "";
        }
        if (SHEET_MIME.equals(mimeType)) {
            return fetchSpreadsheetContent(fileId);
        }
        if (!DOC_MIME.equals(mimeType)) {
            return "";
        }

        String accessToken = ensureValidAccessToken(false);
        if (accessToken == null || accessToken.isEmpty()) {
            return "";
        }

        try {
            String content = exportFile(accessToken, fileId, "text/plain");
            if (content == null) {
                accessToken = ensureValidAccessToken(true);
                content = exportFile(accessToken, fileId, "text/plain");
            }
            return truncate(content);
        } catch (Exception e) {
            logger.warn("Google Drive export failed for fileId={}: {}", fileId, e.getMessage());
            return "";
        }
    }

    /**
     * Read every tab of a spreadsheet via the Sheets API and render each as a
     * labelled CSV-like block. Drive's CSV export only returns the first sheet,
     * which is insufficient for understanding a multi-tab workbook.
     */
    private String fetchSpreadsheetContent(String fileId) {
        String accessToken = ensureValidAccessToken(false);
        if (accessToken == null || accessToken.isEmpty()) {
            return "";
        }
        try {
            List<String> tabTitles = listSheetTitles(accessToken, fileId);
            if (tabTitles == null) {
                accessToken = ensureValidAccessToken(true);
                tabTitles = listSheetTitles(accessToken, fileId);
            }
            if (tabTitles == null || tabTitles.isEmpty()) {
                return "";
            }

            JsonNode batch = batchGetValues(accessToken, fileId, tabTitles);
            if (batch == null) {
                accessToken = ensureValidAccessToken(true);
                batch = batchGetValues(accessToken, fileId, tabTitles);
            }
            if (batch == null) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode valueRange : batch.path("valueRanges")) {
                String range = valueRange.path("range").asText("");
                String tabName = range.contains("!") ? range.substring(0, range.indexOf('!')) : range;
                tabName = tabName.replace("'", "").trim();

                sb.append("### Tab: ").append(tabName).append("\n");
                int rowCount = 0;
                for (JsonNode row : valueRange.path("values")) {
                    if (rowCount++ >= MAX_SHEET_ROWS_PER_TAB) {
                        sb.append("... (more rows omitted)\n");
                        break;
                    }
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) line.append(",");
                        line.append(row.get(i).asText(""));
                    }
                    sb.append(line).append("\n");
                }
                sb.append("\n");
                if (sb.length() > MAX_CONTENT_CHARS) {
                    break;
                }
            }
            return truncate(sb.toString());
        } catch (Exception e) {
            logger.warn("Google Sheets read failed for fileId={}: {}", fileId, e.getMessage());
            return "";
        }
    }

    /** List sheet/tab titles of a spreadsheet. Returns null on auth failure (to allow a refresh+retry). */
    private List<String> listSheetTitles(String token, String fileId) {
        try {
            String response = sheetsWebClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/v4/spreadsheets/{id}")
                    .queryParam("fields", "sheets.properties.title")
                    .queryParam("includeGridData", "false")
                    .build(fileId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                    clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new GoogleAuthException())))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            if (response == null) {
                return List.of();
            }
            List<String> titles = new ArrayList<>();
            for (JsonNode sheet : objectMapper.readTree(response).path("sheets")) {
                String title = sheet.path("properties").path("title").asText("");
                if (!title.isEmpty()) {
                    titles.add(title);
                }
            }
            return titles;
        } catch (GoogleAuthException authEx) {
            return null;
        } catch (Exception e) {
            logger.warn("Google Sheets metadata read failed for fileId={}: {}", fileId, e.getMessage());
            return List.of();
        }
    }

    /** Batch-read values for all given tab ranges. Returns null on auth failure (to allow a refresh+retry). */
    private JsonNode batchGetValues(String token, String fileId, List<String> tabTitles) {
        try {
            String response = sheetsWebClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v4/spreadsheets/{id}/values:batchGet");
                    // Quote tab titles to be safe with spaces/special characters.
                    for (String title : tabTitles) {
                        uriBuilder.queryParam("ranges", "'" + title.replace("'", "''") + "'");
                    }
                    uriBuilder.queryParam("majorDimension", "ROWS");
                    uriBuilder.queryParam("valueRenderOption", "FORMATTED_VALUE");
                    return uriBuilder.build(fileId);
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                    clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new GoogleAuthException())))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            return response == null ? null : objectMapper.readTree(response);
        } catch (GoogleAuthException authEx) {
            return null;
        } catch (Exception e) {
            logger.warn("Google Sheets values read failed for fileId={}: {}", fileId, e.getMessage());
            return null;
        }
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        content = content.replaceAll("\\r\\n?", "\n").trim();
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS) + "...";
        }
        return content;
    }

    private JsonNode listFiles(String token, String q, int maxResults) {
        try {
            String response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/drive/v3/files")
                    .queryParam("q", q)
                    .queryParam("pageSize", Math.max(1, maxResults))
                    .queryParam("fields", "files(id,name,mimeType,webViewLink,modifiedTime)")
                    .queryParam("orderBy", "modifiedTime desc")
                    .queryParam("supportsAllDrives", "true")
                    .queryParam("includeItemsFromAllDrives", "true")
                    .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                    clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(body -> {
                            logger.warn("Google Drive list auth error (HTTP {}): {}",
                                clientResponse.statusCode().value(), body);
                            return Mono.error(new GoogleAuthException());
                        }))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            return response == null ? null : objectMapper.readTree(response);
        } catch (GoogleAuthException authEx) {
            return null;
        } catch (Exception e) {
            logger.error("Google Drive list request failed: {}", e.getMessage());
            return null;
        }
    }

    private String exportFile(String token, String fileId, String exportMime) {
        try {
            return webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/drive/v3/files/{id}/export")
                    .queryParam("mimeType", exportMime)
                    .build(fileId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                    clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new GoogleAuthException())))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        } catch (GoogleAuthException authEx) {
            return null;
        } catch (Exception e) {
            logger.warn("Google Drive export request failed for fileId={}: {}", fileId, e.getMessage());
            return "";
        }
    }

    private boolean canRefreshToken() {
        return userConfig != null
            && userConfig.getGoogleRefreshToken() != null && !userConfig.getGoogleRefreshToken().isEmpty()
            && userConfig.getGoogleClientId() != null && !userConfig.getGoogleClientId().isEmpty()
            && userConfig.getGoogleClientSecret() != null && !userConfig.getGoogleClientSecret().isEmpty();
    }

    private String ensureValidAccessToken(boolean forceRefresh) {
        if (!canRefreshToken()) {
            return userConfig != null ? userConfig.getGoogleAccessToken() : null;
        }
        Long expiresAt = userConfig.getGoogleExpiresAt();
        long now = System.currentTimeMillis();
        boolean nearExpiry = expiresAt == null || (expiresAt - now) <= 120_000L;
        if (!forceRefresh && !nearExpiry
            && userConfig.getGoogleAccessToken() != null && !userConfig.getGoogleAccessToken().isEmpty()) {
            return userConfig.getGoogleAccessToken();
        }
        String refreshed = refreshAccessToken();
        return refreshed != null && !refreshed.isEmpty() ? refreshed : userConfig.getGoogleAccessToken();
    }

    private String refreshAccessToken() {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", userConfig.getGoogleClientId());
            form.add("client_secret", userConfig.getGoogleClientSecret());
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", userConfig.getGoogleRefreshToken());

            String json = tokenWebClient.post()
                .uri("/token")
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

            if (json == null || json.isEmpty()) {
                logger.warn("Google token refresh returned empty response.");
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            String newAccessToken = root.path("access_token").asText("");
            long expiresIn = root.path("expires_in").asLong(0);
            if (newAccessToken.isEmpty()) {
                logger.warn("Google token refresh failed: {} {}",
                    root.path("error").asText(""), root.path("error_description").asText(""));
                return null;
            }
            userConfig.setGoogleAccessToken(newAccessToken);
            if (expiresIn > 0) {
                userConfig.setGoogleExpiresAt(System.currentTimeMillis() + (expiresIn * 1000L) - 60_000L);
            }
            return newAccessToken;
        } catch (Exception e) {
            logger.warn("Google token refresh failed: {}", e.getMessage());
            return null;
        }
    }

    private static class GoogleAuthException extends RuntimeException {
    }
}
