package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.service.TriggerService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.Trigger;
import ai.inquery.spi.sql.InqueryContext;
import org.springframework.stereotype.Service;

@Service
public class TriggerServiceImpl implements TriggerService {
    @Override
    public ListResult<Trigger> triggers(String databaseName, String schemaName) {
        return ListResult.of(InqueryContext.getMetaData().triggers(InqueryContext.getConnection(),databaseName, schemaName));
    }

    @Override
    public DataResult<Trigger> detail(String databaseName, String schemaName, String triggerName) {
        return DataResult.of(InqueryContext.getMetaData().trigger(InqueryContext.getConnection(), databaseName, schemaName, triggerName));
    }
}
