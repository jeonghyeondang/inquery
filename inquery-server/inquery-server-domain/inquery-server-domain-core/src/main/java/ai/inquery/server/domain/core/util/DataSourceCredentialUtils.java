package ai.inquery.server.domain.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.datasource.DataSourcePreConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceUpdateParam;
import ai.inquery.server.tools.common.util.CredentialMaskUtils;
import ai.inquery.spi.model.KeyValue;
import ai.inquery.spi.model.SSHInfo;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Masks datasource secrets for client responses and preserves stored secrets on partial updates.
 */
public final class DataSourceCredentialUtils {

    private DataSourceCredentialUtils() {
    }

    public static void maskForClient(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        dataSource.setPassword(null);
        maskSshForClient(dataSource.getSsh());
        if (CollectionUtils.isEmpty(dataSource.getExtendInfo())) {
            return;
        }
        for (KeyValue keyValue : dataSource.getExtendInfo()) {
            if (keyValue == null || StringUtils.isBlank(keyValue.getKey())) {
                continue;
            }
            if (!CredentialMaskUtils.isSensitiveExtendKey(keyValue.getKey())) {
                continue;
            }
            if ("serviceAccountJson".equalsIgnoreCase(keyValue.getKey())) {
                keyValue.setValue(CredentialMaskUtils.summarizeServiceAccountJson(keyValue.getValue()));
            } else if (StringUtils.isNotBlank(keyValue.getValue())) {
                keyValue.setValue(CredentialMaskUtils.maskToken(keyValue.getValue()));
            }
        }
    }

    public static void mergeUpdateSecrets(DataSource existing, DataSourceUpdateParam param) {
        if (existing == null || param == null) {
            return;
        }
        if (CredentialMaskUtils.shouldPreserveSecret(param.getPassword())) {
            param.setPassword(existing.getPassword());
        }
        param.setExtendInfo(mergeExtendInfo(existing.getExtendInfo(), param.getExtendInfo()));
        mergeSshSecrets(existing.getSsh(), param.getSsh());
    }

    public static void mergePreConnectSecrets(DataSource existing, DataSourcePreConnectParam param) {
        if (existing == null || param == null) {
            return;
        }
        if (CredentialMaskUtils.shouldPreserveSecret(param.getPassword())) {
            param.setPassword(existing.getPassword());
        }
        param.setExtendInfo(mergeExtendInfo(existing.getExtendInfo(), param.getExtendInfo()));
        mergeSshSecrets(existing.getSsh(), param.getSsh());
    }

    private static List<KeyValue> mergeExtendInfo(List<KeyValue> existing, List<KeyValue> incoming) {
        Map<String, String> merged = toMap(existing);
        if (incoming == null) {
            return fromMap(merged);
        }
        for (KeyValue keyValue : incoming) {
            if (keyValue == null || StringUtils.isBlank(keyValue.getKey())) {
                continue;
            }
            String key = keyValue.getKey();
            String value = keyValue.getValue();
            if ("serviceAccountJson".equalsIgnoreCase(key)
                && CredentialMaskUtils.isRedactedServiceAccountSummary(value)) {
                continue;
            }
            if (CredentialMaskUtils.isSensitiveExtendKey(key)
                && CredentialMaskUtils.shouldPreserveSecret(value)) {
                continue;
            }
            merged.put(key, value);
        }
        return fromMap(merged);
    }

    private static void mergeSshSecrets(SSHInfo existing, SSHInfo incoming) {
        if (existing == null || incoming == null) {
            return;
        }
        if (CredentialMaskUtils.shouldPreserveSecret(incoming.getPassword())) {
            incoming.setPassword(existing.getPassword());
        }
        if (CredentialMaskUtils.shouldPreserveSecret(incoming.getPassphrase())) {
            incoming.setPassphrase(existing.getPassphrase());
        }
        if (CredentialMaskUtils.shouldPreserveSecret(incoming.getKeyFile())) {
            incoming.setKeyFile(existing.getKeyFile());
        }
    }

    private static void maskSshForClient(SSHInfo ssh) {
        if (ssh == null) {
            return;
        }
        if (StringUtils.isNotBlank(ssh.getPassword())) {
            ssh.setPassword(CredentialMaskUtils.maskToken(ssh.getPassword()));
        }
        if (StringUtils.isNotBlank(ssh.getPassphrase())) {
            ssh.setPassphrase(CredentialMaskUtils.maskToken(ssh.getPassphrase()));
        }
        if (StringUtils.isNotBlank(ssh.getKeyFile())) {
            ssh.setKeyFile(CredentialMaskUtils.REDACTED);
        }
    }

    private static Map<String, String> toMap(List<KeyValue> extendInfo) {
        Map<String, String> map = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(extendInfo)) {
            return map;
        }
        for (KeyValue keyValue : extendInfo) {
            if (keyValue == null || StringUtils.isBlank(keyValue.getKey())) {
                continue;
            }
            map.put(keyValue.getKey(), keyValue.getValue());
        }
        return map;
    }

    private static List<KeyValue> fromMap(Map<String, String> map) {
        List<KeyValue> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            KeyValue keyValue = new KeyValue();
            keyValue.setKey(entry.getKey());
            keyValue.setValue(entry.getValue());
            list.add(keyValue);
        }
        return list;
    }
}
