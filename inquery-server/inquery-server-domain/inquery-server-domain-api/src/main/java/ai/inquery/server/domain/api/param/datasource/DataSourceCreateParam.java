package ai.inquery.server.domain.api.param.datasource;

import java.util.List;

import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.model.KeyValue;
import ai.inquery.spi.model.SSHInfo;
import ai.inquery.spi.model.SSLInfo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @version DataSourceCreateParam.java, v 0.1 September 23, 2022 15:23 moji Exp $
 */
@Data
public class DataSourceCreateParam {

    /**
     * Alias
     */
    private String alias;

    /**
     * connection address
     */
    private String url;

    /**
     * userName
     */
    private String userName;

    /**
     * password
     */
    private String password;

    /**
     * Database type
     */
    private String type;

    /**
     * environment type
     */
    private String envType;


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
     * Connection Type
     *
     * @see ai.inquery.server.domain.api.enums.DataSourceKindEnum
     */
    private String kind;

    /**
     * environment id
     */
    @NotNull
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
