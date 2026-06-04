package ai.inquery.server.domain.api.param;

import lombok.Data;

/**
 * @version ConsoleConnectParam.java, v 0.1 October 30, 2022 15:53 moji Exp $
 */
@Data
public class ConsoleConnectParam {

    /**
     * Data source id
     */
    private Long dataSourceId;

    /**
     * databaseName
     */
    private String databaseName;

    /**
     * console id
     */
    private Long consoleId;
}
