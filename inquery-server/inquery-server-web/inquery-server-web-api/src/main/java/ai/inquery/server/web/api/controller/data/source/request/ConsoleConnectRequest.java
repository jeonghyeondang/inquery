package ai.inquery.server.web.api.controller.data.source.request;

import lombok.Data;

/**
 * @version ConsoleContentRequest.java, v 0.1 October 30, 2022 15:52 moji Exp $
 */
@Data
public class ConsoleConnectRequest extends DataSourceBaseRequest implements DataSourceConsoleRequestInfo {

    /**
     * console id
     */
    private Long consoleId;
}
