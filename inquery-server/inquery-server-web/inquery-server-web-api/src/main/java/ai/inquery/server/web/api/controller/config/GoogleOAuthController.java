package ai.inquery.server.web.api.controller.config;

import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.repository.mapper.UserAIConfigMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google OAuth (interactive login) controller for Drive/Docs/Sheets access.
 *
 * <p>BYO credentials: the user registers their own OAuth client in Google Cloud
 * Console and supplies the client ID/secret. We perform the authorization-code
 * flow with PKCE, store the resulting refresh token, and auto-refresh thereafter.
 */
@Slf4j
@RequestMapping("/api/config/ai/google/oauth")
@RestController
public class GoogleOAuthController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Map<String, PendingOAuth> PENDING = new ConcurrentHashMap<>();
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient tokenWebClient = WebClient.builder()
        .baseUrl("https://oauth2.googleapis.com")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        .build();

    private UserAIConfigMapper getMapper() {
        return Dbutils.getMapper(UserAIConfigMapper.class);
    }

    @GetMapping("/start")
    public void start(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            response.setStatus(401);
            response.getWriter().write("Unauthorized");
            return;
        }

        Dbutils.setSession();
        try {
            UserAIConfigDO config = loadUserConfig(userId);
            if (config == null || StringUtils.isBlank(config.getGoogleClientId()) || StringUtils.isBlank(config.getGoogleClientSecret())) {
                response.setStatus(400);
                response.getWriter().write("Missing Google OAuth config: clientId and clientSecret are required.");
                return;
            }

            String redirectUri = buildRedirectUri();
            log.info("Google OAuth start - redirectUri: {}", redirectUri);

            String state = randomUrlSafeString(24);
            String codeVerifier = randomUrlSafeString(48);
            String codeChallenge = sha256Base64Url(codeVerifier);

            PENDING.put(state, new PendingOAuth(userId, codeVerifier, System.currentTimeMillis(), redirectUri));

            String authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + urlEncode(config.getGoogleClientId())
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(SCOPE)
                + "&access_type=offline"
                + "&include_granted_scopes=true"
                + "&prompt=consent"
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state);

            response.sendRedirect(authorizeUrl);
        } finally {
            Dbutils.removeSession();
        }
    }

    @GetMapping("/callback")
    public void callback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        HttpServletResponse response
    ) throws Exception {
        response.setContentType("text/html; charset=utf-8");

        if (StringUtils.isNotBlank(error)) {
            response.getWriter().write(htmlResult("Google connection failed", "OAuth failed: " + error, false));
            return;
        }
        if (StringUtils.isBlank(code) || StringUtils.isBlank(state)) {
            response.getWriter().write(htmlResult("Google connection failed", "Missing code/state.", false));
            return;
        }

        PendingOAuth pending = PENDING.remove(state);
        if (pending == null || (System.currentTimeMillis() - pending.createdAtMs) > PENDING_TTL_MS) {
            response.getWriter().write(htmlResult("Google connection failed", "Login session expired. Please try again.", false));
            return;
        }

        // The provider redirect carries no JWT, so the user is resolved from the
        // server-side state→pending mapping created during the authenticated /start.
        // The random, single-use, short-lived `state` is the CSRF protection.
        Long userId = pending.userId;

        Dbutils.setSession();
        try {
            UserAIConfigDO config = loadUserConfig(userId);
            if (config == null || StringUtils.isBlank(config.getGoogleClientId()) || StringUtils.isBlank(config.getGoogleClientSecret())) {
                response.getWriter().write(htmlResult("Google connection failed", "Missing clientId/clientSecret configuration.", false));
                return;
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", config.getGoogleClientId());
            form.add("client_secret", config.getGoogleClientSecret());
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", pending.redirectUri);
            form.add("code_verifier", pending.codeVerifier);

            String json = tokenWebClient.post()
                .uri("/token")
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

            if (StringUtils.isBlank(json)) {
                response.getWriter().write(htmlResult("Google connection failed", "Empty token response.", false));
                return;
            }

            JsonNode root = objectMapper.readTree(json);
            String accessToken = root.path("access_token").asText("");
            String refreshToken = root.path("refresh_token").asText("");
            long expiresIn = root.path("expires_in").asLong(0);

            if (StringUtils.isBlank(refreshToken)) {
                String err = root.path("error").asText("");
                String desc = root.path("error_description").asText("");
                response.getWriter().write(htmlResult(
                    "Google connection failed",
                    "No refresh_token returned. Revoke prior access and retry (prompt=consent + access_type=offline). " + err + " " + desc,
                    false
                ));
                return;
            }

            long expiresAt = expiresIn > 0
                ? (System.currentTimeMillis() + (expiresIn * 1000L) - 60_000L)
                : 0L;

            config.setGoogleAccessToken(accessToken);
            config.setGoogleRefreshToken(refreshToken);
            if (expiresAt > 0) {
                config.setGoogleExpiresAt(expiresAt);
            }
            getMapper().updateById(config);

            response.getWriter().write(htmlResult("Google connected", "Google Drive is now connected. You can close this window.", true));
        } catch (Exception e) {
            log.error("Google OAuth callback failed", e);
            response.getWriter().write(htmlResult("Google connection failed", e.getMessage(), false));
        } finally {
            Dbutils.removeSession();
        }
    }

    @PostMapping("/disconnect")
    public ActionResult disconnect() {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return ActionResult.fail("Unauthorized", "Unauthorized", "UNAUTHORIZED");
        }

        Dbutils.setSession();
        try {
            UserAIConfigDO config = loadUserConfig(userId);
            if (config == null) {
                return ActionResult.isSuccess();
            }
            LambdaUpdateWrapper<UserAIConfigDO> update = new LambdaUpdateWrapper<>();
            update.eq(UserAIConfigDO::getId, config.getId())
                .set(UserAIConfigDO::getGoogleAccessToken, null)
                .set(UserAIConfigDO::getGoogleRefreshToken, null)
                .set(UserAIConfigDO::getGoogleExpiresAt, null);
            getMapper().update(null, update);
            return ActionResult.isSuccess();
        } finally {
            Dbutils.removeSession();
        }
    }

    private String buildRedirectUri() {
        String redirectUri = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/config/ai/google/oauth/callback")
            .build()
            .toUriString();
        // Google treats localhost and 127.0.0.1 as distinct redirect URIs; normalize to localhost.
        return redirectUri.replace("://127.0.0.1:", "://localhost:");
    }

    private UserAIConfigDO loadUserConfig(Long userId) {
        LambdaQueryWrapper<UserAIConfigDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAIConfigDO::getUserId, userId);
        return getMapper().selectOne(wrapper);
    }

    private static String randomUrlSafeString(int bytes) {
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Base64Url(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String htmlResult(String title, String message, boolean success) {
        String status = success ? "success" : "error";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escapeHtml(title) + "</title></head>"
            + "<body style=\"font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; padding: 16px;\">"
            + "<h3>" + escapeHtml(title) + "</h3>"
            + "<p>" + escapeHtml(message) + "</p>"
            + "<script>"
            + "try { if (window.opener) { window.opener.postMessage({ type: 'GOOGLE_OAUTH', status: '" + status + "' }, '*'); } } catch (e) {}"
            + "try { window.close(); } catch (e) {}"
            + "</script>"
            + "</body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record PendingOAuth(Long userId, String codeVerifier, long createdAtMs, String redirectUri) {}
}
