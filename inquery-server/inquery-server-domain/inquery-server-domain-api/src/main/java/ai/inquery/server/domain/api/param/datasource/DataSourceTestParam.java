package ai.inquery.server.domain.api.param.datasource;

import java.util.List;

import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.model.KeyValue;
import ai.inquery.spi.model.SSHInfo;
import ai.inquery.spi.model.SSLInfo;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Data source test parameters
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceTestParam {

    /**
     * Database type
     *
     * @see DbTypeEnum
     */
    @NotNull
    private String dbType;

    /**
     * Request connection
     */
    @NotNull
    private String url;

    /**
     * userName
     */
    private String username;

    /**
     * password
     */
    private String password;

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
}
