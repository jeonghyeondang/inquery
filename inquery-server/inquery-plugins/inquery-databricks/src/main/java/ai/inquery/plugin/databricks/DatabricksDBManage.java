package ai.inquery.plugin.databricks;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.jdbc.DefaultDBManage;
import ai.inquery.spi.sql.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;

/**
 * Databricks database connection manager.
 * Handles JDBC connection with Personal Access Token authentication.
 */
@Slf4j
public class DatabricksDBManage extends DefaultDBManage implements DBManage {

    private static final String HTTP_PATH = "httpPath";
    private static final String ACCESS_TOKEN = "accessToken";
    private static final String CATALOG = "catalog";
    private static final String SCHEMA = "schema";

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        Map<String, Object> extendMap = connectInfo.getExtendMap();

        String httpPath = extendMap != null ? (String) extendMap.get(HTTP_PATH) : null;
        String accessToken = extendMap != null ? (String) extendMap.get(ACCESS_TOKEN) : null;
        String catalog = extendMap != null ? (String) extendMap.get(CATALOG) : null;
        String schema = extendMap != null ? (String) extendMap.get(SCHEMA) : null;

        if (StringUtils.isBlank(httpPath)) {
            throw new IllegalArgumentException("HTTP Path is required for Databricks connection");
        }

        if (StringUtils.isBlank(accessToken)) {
            throw new IllegalArgumentException("Personal Access Token is required for Databricks connection");
        }

        String host = connectInfo.getHost();
        Integer port = connectInfo.getPort();
        
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("Host is required for Databricks connection");
        }

        log.info("Databricks connection request - host: {}, httpPath: {}", host, httpPath);

        try {
            // Load driver class (from Maven dependency)
            Class.forName("com.databricks.client.jdbc.Driver");
            
            // Build JDBC URL
            String url = buildJdbcUrl(host, port, httpPath, catalog, schema);

            Properties properties = new Properties();
            // Authentication using Personal Access Token (as per official Databricks docs)
            properties.put("PWD", accessToken);

            log.info("Databricks JDBC URL: {}", url);

            // Use standard DriverManager since driver is in classpath via Maven
            Connection connection = DriverManager.getConnection(url, properties);

            // Validate the connection can actually execute queries
            try (java.sql.Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(30);
                stmt.execute("SELECT 1");
            } catch (java.sql.SQLException e) {
                connection.close();
                String msg = e.getMessage();
                if (msg != null && (msg.contains("PERMISSION_DENIED") || msg.contains("Access Denied") || msg.contains("UNAUTHORIZED"))) {
                    throw new RuntimeException(
                        "The access token does not have sufficient permissions. " +
                        "Please verify the Personal Access Token and ensure the user has query execution privileges.", e);
                }
                throw e;
            }

            connectInfo.setConnection(connection);

            log.info("Databricks connection established successfully for host: {}", host);
            return connection;

        } catch (ClassNotFoundException e) {
            log.error("Databricks JDBC driver not found: {}", e.getMessage(), e);
            throw new RuntimeException("Databricks JDBC driver not found. Make sure databricks-jdbc dependency is included.", e);
        } catch (Exception e) {
            log.error("Failed to establish Databricks connection: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to connect to Databricks: " + e.getMessage(), e);
        }
    }

    /**
     * Build Databricks JDBC URL with connection parameters.
     *
     * Databricks JDBC URL format (from official docs):
     * jdbc:databricks://<host>:<port>;HttpPath=<http-path>
     * 
     * Authentication is done via Properties (PWD = access token)
     */
    private String buildJdbcUrl(String host, Integer port, String httpPath, String catalog, String schema) {
        StringBuilder url = new StringBuilder();

        // Base URL
        url.append("jdbc:databricks://").append(host);
        
        // Port (default 443 for HTTPS)
        if (port != null && port > 0) {
            url.append(":").append(port);
        } else {
            url.append(":443");
        }

        // HTTP Path (required) - Note: Capital H and P as per official docs
        url.append(";HttpPath=").append(httpPath);

        // Catalog (Unity Catalog)
        if (StringUtils.isNotBlank(catalog)) {
            url.append(";ConnCatalog=").append(catalog);
        }

        // Default schema
        if (StringUtils.isNotBlank(schema)) {
            url.append(";ConnSchema=").append(schema);
        }

        return url.toString();
    }
}
