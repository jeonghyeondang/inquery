package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.ConsoleConnectParam;
import ai.inquery.server.domain.api.service.ConsoleService;
import ai.inquery.server.domain.api.param.ConsoleCloseParam;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.SQLExecutor;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;

import org.springframework.stereotype.Service;

/**
 * @version DataSourceCoreServiceImpl.java, v 0.1 September 23, 2022 15:51 moji Exp $
 */
@Service
public class ConsoleServiceImpl implements ConsoleService {
    @Override
    public ActionResult createConsole(ConsoleConnectParam param) {
        InqueryContext.getDBManage().connectDatabase(InqueryContext.getConnection(),param.getDatabaseName());
        return ActionResult.isSuccess();
    }

    @Override
    public ActionResult closeConsole(ConsoleCloseParam param) {
        return ActionResult.isSuccess();
    }

}
