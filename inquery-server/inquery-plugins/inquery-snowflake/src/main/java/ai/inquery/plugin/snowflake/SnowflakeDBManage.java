package ai.inquery.plugin.snowflake;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.jdbc.DefaultDBManage;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.IDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Slf4j
public class SnowflakeDBManage extends DefaultDBManage implements DBManage {

    private static final String PRIVATE_KEY_FILE = "privateKeyFile";
    private static final String PRIVATE_KEY_CONTENT = "privateKeyContent";
    private static final String PRIVATE_KEY_PASSPHRASE = "privateKeyPassphrase";
    private static final String PROPERTY_RESULT_FORMAT = "JDBC_QUERY_RESULT_FORMAT";
    private static final String PROPERTY_CHUNK_SIZE = "CLIENT_RESULT_CHUNK_SIZE";
    private static final String PROPERTY_PREFETCH_THREADS = "CLIENT_PREFETCH_THREADS";

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        Map<String, Object> extendMap = connectInfo.getExtendMap();
        String authType = extendMap != null ? (String) extendMap.get("authenticationType") : null;
        boolean useKeypair = StringUtils.equalsIgnoreCase("keypair", authType);

        if (useKeypair) {
            log.info("Snowflake connection: keypair auth");
            return createKeyPairConnection(connectInfo, extendMap);
        } else {
            log.info("Snowflake connection: password auth");
            return createPasswordConnection(connectInfo, extendMap);
        }
    }

    private Connection createPasswordConnection(ConnectInfo connectInfo, Map<String, Object> extendMap) {
        if (StringUtils.isBlank(connectInfo.getUser())) {
            throw new RuntimeException("Snowflake password authentication requires a username");
        }
        if (StringUtils.isBlank(connectInfo.getPassword())) {
            throw new RuntimeException(
                "Snowflake password is missing. The password may have been lost during a data source update. "
                + "Please edit this data source and re-enter the password, or configure key-pair authentication.");
        }

        Properties properties = buildBaseProperties(extendMap);
        properties.put("user", connectInfo.getUser());
        properties.put("password", connectInfo.getPassword());

        return finalizeConnection(connectInfo, properties, "password");
    }

    private Connection createKeyPairConnection(ConnectInfo connectInfo, Map<String, Object> extendMap) {
        Properties properties = buildBaseProperties(extendMap);

        if (StringUtils.isNotBlank(connectInfo.getUser())) {
            properties.put("user", connectInfo.getUser());
        }

        try {
            PrivateKey privateKey = loadPrivateKey(extendMap);
            properties.put("privateKey", privateKey);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load Snowflake private key: " + ex.getMessage(), ex);
        }

        return finalizeConnection(connectInfo, properties, "keypair");
    }

    private Properties buildBaseProperties(Map<String, Object> extendMap) {
        Properties properties = new Properties();

        if (extendMap != null && !extendMap.isEmpty()) {
            for (Map.Entry<String, Object> entry : extendMap.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                String key = entry.getKey();
                if ("authenticationType".equals(key)
                    || PRIVATE_KEY_FILE.equals(key)
                    || PRIVATE_KEY_CONTENT.equals(key)
                    || PRIVATE_KEY_PASSPHRASE.equals(key)
                    || PROPERTY_RESULT_FORMAT.equals(key)
                    || PROPERTY_CHUNK_SIZE.equals(key)
                    || PROPERTY_PREFETCH_THREADS.equals(key)) {
                    continue;
                }
                properties.put(key, value);
            }
        }

        // Force Arrow format (recommended in Snowflake docs)
        properties.put(PROPERTY_RESULT_FORMAT, "ARROW");
        properties.put(PROPERTY_CHUNK_SIZE, "128");  // Snowflake allowed values: 16, 32, 64, 128
        properties.put(PROPERTY_PREFETCH_THREADS, "10");  // Increase prefetch threads
        properties.put("CLIENT_SESSION_KEEP_ALIVE", "true");  // Keep session alive

        return properties;
    }

    private Connection finalizeConnection(ConnectInfo connectInfo, Properties properties, String authMode) {
        String url = connectInfo.getUrl();
        
        if (!url.contains("JDBC_QUERY_RESULT_FORMAT")) {
            String separator = url.contains("?") ? "&" : "?";
            url = url + separator + "JDBC_QUERY_RESULT_FORMAT=ARROW";
        } else {
            url = url.replaceAll("JDBC_QUERY_RESULT_FORMAT=[A-Z]+", "JDBC_QUERY_RESULT_FORMAT=ARROW");
        }
        
        log.info("Snowflake connection URL (Arrow ONLY): {}", url);
        
        Connection connection = null;
        try {
            connection = IDriverManager.getConnection(url, properties, connectInfo.getDriverConfig());
            
            enforceArrowSession(connection);
            
            log.info("Arrow format enforced for {} auth - connection established", authMode);

            connectInfo.setConnection(connection);
            if (StringUtils.isNotBlank(connectInfo.getDatabaseName()) || StringUtils.isNotBlank(connectInfo.getSchemaName())) {
                connectDatabase(connection, connectInfo.getDatabaseName());
            }
            return connection;
        } catch (Throwable ex) {
            closeQuietly(connection);
            throw new RuntimeException("Snowflake connection failed (" + authMode + " auth): " + ex.getMessage(), ex);
        }
    }

    private PrivateKey loadPrivateKey(Map<String, Object> extendMap) throws Exception {
        if (extendMap == null) {
            throw new IllegalArgumentException("Private key configuration is missing");
        }
        String privateKeyContent = (String) extendMap.get(PRIVATE_KEY_CONTENT);
        String privateKeyFile = (String) extendMap.get(PRIVATE_KEY_FILE);
        String passphrase = (String) extendMap.get(PRIVATE_KEY_PASSPHRASE);

        if (StringUtils.isNotBlank(privateKeyContent)) {
            return parsePrivateKey(privateKeyContent, passphrase);
        }
        if (StringUtils.isNotBlank(privateKeyFile)) {
            String keyContent = new String(Files.readAllBytes(Paths.get(privateKeyFile)), StandardCharsets.UTF_8);
            return parsePrivateKey(keyContent, passphrase);
        }
        throw new IllegalArgumentException("Private key file or content must be provided for key-pair authentication");
    }

    private void enforceArrowSession(Connection connection) throws SQLException {
        if (connection == null) {
            throw new SQLException("Connection is null");
        }

        try (Statement statement = connection.createStatement()) {
            // Force Arrow format at session level (recommended in docs)
            statement.execute("ALTER SESSION SET JDBC_QUERY_RESULT_FORMAT = 'ARROW'");
            
            // Performance tuning (Snowflake-allowed values)
            statement.execute("ALTER SESSION SET CLIENT_RESULT_CHUNK_SIZE = 128");
            statement.execute("ALTER SESSION SET CLIENT_PREFETCH_THREADS = 10");
            statement.execute("ALTER SESSION SET CLIENT_SESSION_KEEP_ALIVE = TRUE");
            
            log.info("Arrow format enforced at session level - chunkSize: 128, prefetchThreads: 10");
        } catch (SQLException e) {
            log.error("CRITICAL: Failed to enforce Arrow format at session level: {}", e.getMessage());
            throw e;  // Fail connection if Arrow cannot be enforced
        }
    }

    private void closeQuietly(Connection connection) {
        if (Objects.isNull(connection)) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignore) {
        }
    }

    private PrivateKey parsePrivateKey(String keyContent, String passphrase) throws Exception {
        boolean isEncrypted = keyContent.contains("ENCRYPTED PRIVATE KEY");

        if (isEncrypted && StringUtils.isNotBlank(passphrase)) {
            throw new IllegalArgumentException(
                "Encrypted private keys with passphrase are not currently supported. "
                    + "Please use an unencrypted private key or decrypt it first using: "
                    + "openssl rsa -in encrypted_key.pem -out decrypted_key.pem"
            );
        }

        if (isEncrypted) {
            throw new IllegalArgumentException(
                "Encrypted private key detected but no passphrase provided. "
                    + "Please provide the passphrase or decrypt the key first."
            );
        }

        String privateKeyPEM = keyContent
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

}
