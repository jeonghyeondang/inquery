package ai.inquery.plugin.bigquery;

import ai.inquery.spi.jdbc.DefaultDBManage;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.IDriverManager;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class BigQueryDBManage extends DefaultDBManage {

    private static final String SERVICE_ACCOUNT_JSON = "serviceAccountJson";
    private static final String DEFAULT_DATASET = "defaultDataset";

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        Map<String, Object> extendMap = connectInfo.getExtendMap();

        String serviceAccountJson = extendMap != null ? (String) extendMap.get(SERVICE_ACCOUNT_JSON) : null;
        String defaultDataset = extendMap != null ? (String) extendMap.get(DEFAULT_DATASET) : null;

        if (StringUtils.isBlank(serviceAccountJson)) {
            throw new IllegalArgumentException("Service Account JSON is required for BigQuery connection");
        }

        // Extract project_id from Service Account JSON
        String projectId = extractField(serviceAccountJson, "project_id");
        if (StringUtils.isBlank(projectId)) {
            throw new IllegalArgumentException("project_id not found in Service Account JSON");
        }

        // Extract client_email from Service Account JSON
        String serviceAcctEmail = extractClientEmail(serviceAccountJson);
        if (StringUtils.isBlank(serviceAcctEmail)) {
            throw new IllegalArgumentException("client_email not found in Service Account JSON");
        }

        log.info("BigQuery connection request - projectId: {}, serviceAcctEmail: {}", projectId, serviceAcctEmail);

        try {
            // Create temporary file for service account JSON
            File tempKeyFile = createTempServiceAccountFile(serviceAccountJson);
            String keyFilePath = tempKeyFile.getAbsolutePath();

            // Build JDBC URL
            String url = buildJdbcUrl(connectInfo.getUrl(), projectId, serviceAcctEmail, defaultDataset, keyFilePath);

            Properties properties = new Properties();
            // Additional properties can be set here if needed

            log.info("BigQuery JDBC URL: {}", url.replaceAll("OAuthPvtKeyPath=[^;]+", "OAuthPvtKeyPath=***"));

            Connection connection = IDriverManager.getConnection(url, properties, connectInfo.getDriverConfig());

            // Validate the service account has bigquery.jobs.create permission
            // The JDBC driver creates a connection object without checking permissions,
            // so we run a minimal query to verify early instead of failing later.
            try (java.sql.Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(30);
                stmt.execute("SELECT 1");
            } catch (java.sql.SQLException e) {
                connection.close();
                String msg = e.getMessage();
                if (msg != null && (msg.contains("bigquery.jobs.create") || msg.contains("PERMISSION_DENIED") || msg.contains("Access Denied"))) {
                    throw new RuntimeException(
                        "The service account does not have 'bigquery.jobs.create' permission in this project. " +
                        "Please grant the 'BigQuery Job User' role (roles/bigquery.jobUser) to the service account.", e);
                }
                throw e;
            }

            connectInfo.setConnection(connection);

            log.info("BigQuery connection established successfully for project: {}", projectId);
            return connection;

        } catch (Exception e) {
            log.error("Failed to establish BigQuery connection: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to connect to BigQuery: " + e.getMessage(), e);
        }
    }

    /**
     * Extract a string field value from JSON using regex.
     */
    private String extractField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Extract client_email from Service Account JSON using regex.
     */
    private String extractClientEmail(String serviceAccountJson) {
        return extractField(serviceAccountJson, "client_email");
    }

    /**
     * Create a temporary file containing the service account JSON.
     * The Simba BigQuery JDBC driver requires a file path for the service account key.
     */
    private File createTempServiceAccountFile(String serviceAccountJson) throws IOException {
        File tempFile = Files.createTempFile("bigquery_service_account_", ".json").toFile();
        tempFile.deleteOnExit();

        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(serviceAccountJson);
        }

        log.debug("Created temporary service account file: {}", tempFile.getAbsolutePath());
        return tempFile;
    }

    /**
     * Build BigQuery JDBC URL with authentication parameters.
     *
     * Simba BigQuery JDBC URL format:
     * jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;
     *   ProjectId=<project>;
     *   OAuthType=0;
     *   OAuthServiceAcctEmail=<email>;
     *   OAuthPvtKeyPath=<path>;
     *   DefaultDataset=<dataset>;
     */
    private String buildJdbcUrl(String baseUrl, String projectId, String serviceAcctEmail, String defaultDataset, String keyFilePath) {
        StringBuilder url = new StringBuilder();

        // Base URL
        if (StringUtils.isNotBlank(baseUrl)) {
            url.append(baseUrl);
        } else {
            url.append("jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443");
        }

        // Ensure URL ends with semicolon for parameters
        if (!url.toString().endsWith(";")) {
            url.append(";");
        }

        // Project ID
        url.append("ProjectId=").append(projectId).append(";");

        // OAuth Type 0 = Service Account
        url.append("OAuthType=0;");

        // Service Account Email (required by Simba driver)
        url.append("OAuthServiceAcctEmail=").append(serviceAcctEmail).append(";");

        // Service Account Key File Path
        url.append("OAuthPvtKeyPath=").append(keyFilePath).append(";");

        // Default Dataset (optional)
        if (StringUtils.isNotBlank(defaultDataset)) {
            url.append("DefaultDataset=").append(defaultDataset).append(";");
        }

        // Timeout settings
        url.append("Timeout=300;");

        return url.toString();
    }

    /**
     * Execute a DRY_RUN query to estimate resource usage without actually running the query.
     * This uses the BigQuery SDK instead of JDBC.
     *
     * @param connectInfo Connection info containing credentials
     * @param sql The SQL query to estimate
     * @return Map containing estimation results (totalBytesProcessed, etc.)
     */
    public Map<String, Object> dryRun(ConnectInfo connectInfo, String sql) {
        Map<String, Object> extendMap = connectInfo.getExtendMap();
        
        String serviceAccountJson = extendMap != null ? (String) extendMap.get(SERVICE_ACCOUNT_JSON) : null;

        if (StringUtils.isBlank(serviceAccountJson)) {
            throw new IllegalArgumentException("Service Account JSON is required for BigQuery DRY_RUN");
        }

        String projectId = extractField(serviceAccountJson, "project_id");
        if (StringUtils.isBlank(projectId)) {
            throw new IllegalArgumentException("project_id not found in Service Account JSON");
        }

        Map<String, Object> result = new HashMap<>();

        try {
            // Create credentials from service account JSON
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
                new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))
            );

            // Build BigQuery client
            BigQuery bigquery = BigQueryOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(credentials)
                .build()
                .getService();

            // Configure DRY_RUN query
            QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(sql)
                .setDryRun(true)
                .setUseQueryCache(false)
                .build();

            // Execute dry run
            Job job = bigquery.create(JobInfo.of(queryConfig));
            JobStatistics.QueryStatistics stats = job.getStatistics();

            // Extract statistics
            Long totalBytesProcessed = stats.getTotalBytesProcessed();
            Long totalBytesBilled = stats.getTotalBytesBilled();
            Long estimatedBytesProcessed = stats.getEstimatedBytesProcessed();
            Boolean cacheHit = stats.getCacheHit();

            result.put("success", true);
            result.put("totalBytesProcessed", totalBytesProcessed != null ? totalBytesProcessed : 0L);
            result.put("totalBytesBilled", totalBytesBilled != null ? totalBytesBilled : 0L);
            result.put("estimatedBytesProcessed", estimatedBytesProcessed != null ? estimatedBytesProcessed : totalBytesProcessed);
            result.put("cacheHit", cacheHit != null ? cacheHit : false);
            
            // Calculate estimated cost ($5 per TB)
            long bytes = totalBytesProcessed != null ? totalBytesProcessed : 0L;
            double costPerTB = 5.0;
            double estimatedCost = (bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0)) * costPerTB;
            result.put("estimatedCostUSD", Math.round(estimatedCost * 10000.0) / 10000.0); // Round to 4 decimal places

            log.info("BigQuery DRY_RUN completed - bytesProcessed: {}, estimatedCost: ${}", 
                totalBytesProcessed, result.get("estimatedCostUSD"));

        } catch (BigQueryException e) {
            log.error("BigQuery DRY_RUN failed: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorReason", e.getReason());
        } catch (Exception e) {
            log.error("BigQuery DRY_RUN failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
}

