package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.service.FunctionService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.Function;
import ai.inquery.spi.sql.InqueryContext;
import org.springframework.stereotype.Service;

@Service
public class FunctionServiceImpl implements FunctionService {
    @Override
    public ListResult<Function> functions(String databaseName, String schemaName) {
        return ListResult.of(InqueryContext.getMetaData().functions(InqueryContext.getConnection(),databaseName, schemaName));
    }

    @Override
    public DataResult<Function> detail(String databaseName, String schemaName, String functionName) {
        return DataResult.of(InqueryContext.getMetaData().function(InqueryContext.getConnection(), databaseName, schemaName, functionName));
    }

    @Override
    public ActionResult delete(String databaseName, String schemaName, Function function) {
        InqueryContext.getDBManage().deleteFunction(InqueryContext.getConnection(), databaseName, schemaName, function);
        return ActionResult.isSuccess();
    }
}
