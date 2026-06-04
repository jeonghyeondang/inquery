package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.service.ProcedureService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.Procedure;
import ai.inquery.spi.sql.InqueryContext;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

@Service
public class ProcedureServiceImpl implements ProcedureService {

    @Override
    public ListResult<Procedure> procedures(String databaseName, String schemaName) {
        return ListResult.of(InqueryContext.getMetaData().procedures(InqueryContext.getConnection(),databaseName, schemaName));
    }

    @Override
    public DataResult<Procedure> detail(String databaseName, String schemaName, String procedureName) {
        return DataResult.of(InqueryContext.getMetaData().procedure(InqueryContext.getConnection(), databaseName, schemaName, procedureName));
    }
    @Override
    public ActionResult update(String databaseName, String schemaName, Procedure procedure) throws SQLException {
        InqueryContext.getDBManage().updateProcedure(InqueryContext.getConnection(), databaseName, schemaName, procedure);
        return ActionResult.isSuccess();
    }

    @Override
    public ActionResult delete(String databaseName, String schemaName, Procedure procedure) {
        InqueryContext.getDBManage().deleteProcedure(InqueryContext.getConnection(), databaseName, schemaName, procedure);
        return ActionResult.isSuccess();
    }
}
