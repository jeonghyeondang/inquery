package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.ConsoleConnectParam;
import ai.inquery.server.domain.api.param.ConsoleCloseParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;

/**
 * Data source management services
 *
 * @version DataSourceCoreService.java, v 0.1 September 23, 2022 15:22 moji Exp $
 */
public interface ConsoleService {

    /**
     * Create console link
     *
     * @param param
     * @return
     */
    ActionResult createConsole(ConsoleConnectParam param);

    /**
     * close connection
     *
     * @param param
     * @return
     */
    ActionResult closeConsole(ConsoleCloseParam param);

}
