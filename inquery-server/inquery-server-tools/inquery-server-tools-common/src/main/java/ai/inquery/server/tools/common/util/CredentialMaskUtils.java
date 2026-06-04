package ai.inquery.server.tools.common.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

/**
 * Redacts credentials in API responses and HTTP logs.
 */
public final class CredentialMaskUtils {

    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_JSON_KEYS = Set.of(
        "password",
        "private_key",
        "privatekey",
        "privatekeycontent",
        "privatekeypassphrase",
        "serviceaccountjson",
        "accesstoken",
        "refreshtoken",
        "apikey",
        "apisecret",
        "secret",
        "client_secret",
        "bot_token",
        "app_token",
        "credentials",
        "token"
    );

    private static final Pattern JSON_STRING_FIELD = Pattern.compile(
        "\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern EXTEND_INFO_SECRET_VALUE = Pattern.compile(
        "\"key\"\\s*:\\s*\"(serviceAccountJson|privateKeyContent|privateKeyPassphrase|accessToken)\"\\s*,\\s*\"value\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN [A-Z ]+-----[\\s\\S]*?-----END [A-Z ]+-----",
        Pattern.MULTILINE);

    private CredentialMaskUtils() {
    }

    /**
     * Returns true when the client did not supply a new secret value.
     */
    public static boolean shouldPreserveSecret(String value) {
        if (StringUtils.isBlank(value)) {
            return true;
        }
        if (REDACTED.equals(value)) {
            return true;
        }
        return value.contains("***");
    }

    /**
     * Partial mask for short token-like values (Slack-style).
     */
    public static String maskToken(String token) {
        if (StringUtils.isBlank(token)) {
            return token;
        }
        if (token.length() < 10) {
            return REDACTED;
        }
        return token.substring(0, 6) + "***" + token.substring(token.length() - 4);
    }

    public static boolean isSensitiveExtendKey(String key) {
        if (StringUtils.isBlank(key)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if ("defaultdataset".equals(lower) || "authenticationtype".equals(lower)
            || "warehouse".equals(lower) || "role".equals(lower) || "schema".equals(lower)
            || "catalog".equals(lower) || "httppath".equals(lower) || "account".equals(lower)) {
            return false;
        }
        if ("serviceaccountjson".equals(lower) || "privatekeycontent".equals(lower)
            || "privatekeypassphrase".equals(lower) || "accesstoken".equals(lower)) {
            return true;
        }
        return lower.contains("password") || lower.contains("secret")
            || lower.contains("privatekey") || lower.contains("private_key")
            || lower.endsWith("token") || lower.contains("credentials") || lower.contains("apikey");
    }

    public static String summarizeServiceAccountJson(String json) {
        if (StringUtils.isBlank(json)) {
            return json;
        }
        try {
            JSONObject obj = JSON.parseObject(json);
            JSONObject summary = new JSONObject();
            if (obj.containsKey("client_email")) {
                summary.put("client_email", obj.getString("client_email"));
            }
            if (obj.containsKey("project_id")) {
                summary.put("project_id", obj.getString("project_id"));
            }
            summary.put("private_key", REDACTED);
            return summary.toJSONString();
        } catch (Exception ignored) {
            return REDACTED;
        }
    }

    public static boolean isRedactedServiceAccountSummary(String value) {
        if (StringUtils.isBlank(value) || REDACTED.equals(value)) {
            return true;
        }
        try {
            JSONObject obj = JSON.parseObject(value);
            String privateKey = obj.getString("private_key");
            return REDACTED.equals(privateKey) || StringUtils.isBlank(privateKey);
        } catch (Exception ignored) {
            return value.contains(REDACTED);
        }
    }

    /**
     * Redacts known secret fields and PEM blocks from log/API text.
     */
    public static String redactSensitiveContent(String input) {
        if (StringUtils.isBlank(input)) {
            return input;
        }
        String redacted = redactPemBlocks(input);
        redacted = redactExtendInfoValues(redacted);
        redacted = redactJsonStringFields(redacted);
        return redacted;
    }

    private static String redactExtendInfoValues(String input) {
        Matcher matcher = EXTEND_INFO_SECRET_VALUE.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            matcher.appendReplacement(buffer,
                Matcher.quoteReplacement("\"key\":\"" + key + "\",\"value\":\"" + REDACTED + "\""));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String redactPemBlocks(String input) {
        Matcher matcher = PEM_BLOCK.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(REDACTED));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String redactJsonStringFields(String input) {
        Matcher matcher = JSON_STRING_FIELD.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (isSensitiveJsonKey(key)) {
                matcher.appendReplacement(buffer,
                    Matcher.quoteReplacement("\"" + key + "\":\"" + REDACTED + "\""));
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isSensitiveJsonKey(String key) {
        if (StringUtils.isBlank(key)) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        if (SENSITIVE_JSON_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.contains("password") || normalized.contains("secret")
            || normalized.contains("privatekey") || normalized.endsWith("token")
            || normalized.contains("credentials");
    }
}
