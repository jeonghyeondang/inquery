package ai.inquery.server.admin.api.controller.datasource.request;

import java.util.List;

import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.model.KeyValue;
import ai.inquery.spi.model.SSHInfo;
import ai.inquery.spi.model.SSLInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @version ConnectionCreateRequest.java, v 0.1 September 16, 2022 14:23 moji Exp $
 */
@Data
public class DataSourceCreateRequest {

    /**
     * Connection alias
     */
    private String alias;

    /**
     * connection address
     */
    @NotNull
    private String url;

    /**
     * Connect username
     */
    private String user;

    /**
     * password (optional for key-pair authentication like Snowflake)
     */
    private String password;

    /**
     * Certification type
     */
    private String authenticationType;

    /**
     * Connection Type
     */
    @NotNull
    private String type;

    /**
     * host
     */
    private String host;

    /**
     * port
     */
    private String port;

    /**
     * ssh
     */
    private SSHInfo ssh;

    /**
     * ssh
     */
    private SSLInfo ssl;

    /**
     * sid
     */
    private String sid;

    /**
     * driver
     */
    private String driver;


    /**
     * jdbc version
     */
    private String jdbc;

    /**
     * Extended Information
     */
    private List<KeyValue> extendInfo;

    /**
     * Driver configuration
     */
    private DriverConfig driverConfig;

    /**
     * environment id
     */
    private Long environmentId;

    /**
     * service name
     */
    private String serviceName;

    /**
     * Service type
     */
    private String serviceType;


}
