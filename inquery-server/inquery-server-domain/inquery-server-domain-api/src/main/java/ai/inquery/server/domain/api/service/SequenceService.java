package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.DropParam;
import ai.inquery.server.domain.api.param.SequencePageQueryParam;
import ai.inquery.server.domain.api.param.SequenceQueryParam;
import ai.inquery.server.domain.api.param.ShowCreateSequenceParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.model.Sequence;
import ai.inquery.spi.model.SimpleSequence;
import ai.inquery.spi.model.Sql;

/**
 * Sequence source management services
 *
 */
public interface SequenceService {
    DataResult<String> showCreateSequence(ShowCreateSequenceParam request);

    ListResult<SimpleSequence> pageQuery(SequencePageQueryParam request);

    ListResult<Sql> buildSql(Sequence oldSequence, Sequence newSequence);

    ActionResult drop(DropParam dropParam);

    DataResult<Sequence> query(SequenceQueryParam queryParam);
}
