package ai.inquery.server.domain.api.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import ai.inquery.spi.config.DriverConfig;
import ai.inquery.spi.model.KeyValue;
import ai.inquery.spi.model.SSHInfo;
import ai.inquery.spi.model.SSLInfo;
import lombok.Data;
import org.springframework.util.ObjectUtils;

/**
 * @version DataSourceDTO.java, v 0.1 September 23, 2022 15:39 moji Exp $
 */
@Data
public class DataSource {

    /**
     * primary key
     */
    private Long id;

    /**
     * creation time
     */
    private LocalDateTime gmtCreate;

    /**
     * modified time
     */
    private LocalDateTime gmtModified;

    /**
     * Alias
     */
    private String alias;

    /**
     * connection address
     */
    private String url;

    /**
     * user name
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
     * environment id
     */
    private Long environmentId;

    /**
     * environment
     */
    private Environment environment;

    /**
     * user id
     */
    private Long userId;


    /**
     * Connection Type
     *
     * @see ai.inquery.server.domain.api.enums.DataSourceKindEnum
     */
    private String kind;


    /**
     * Service name
     */
    private String serviceName;

    /**
     * Service type
     */
    private String serviceType;


    private boolean supportDatabase;

    private boolean supportSchema;

    public LinkedHashMap<String, Object> getExtendMap() {
        if (ObjectUtils.isEmpty(extendInfo)) {
            return new LinkedHashMap<>();
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (KeyValue keyValue : extendInfo) {
            map.put(keyValue.getKey(), keyValue.getValue());
        }
        return map;
    }
}
