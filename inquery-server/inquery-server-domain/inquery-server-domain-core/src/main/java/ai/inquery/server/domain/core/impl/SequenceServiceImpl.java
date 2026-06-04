package ai.inquery.server.domain.core.impl;


import ai.inquery.server.domain.api.param.DropParam;
import ai.inquery.server.domain.api.param.SequencePageQueryParam;
import ai.inquery.server.domain.api.param.SequenceQueryParam;
import ai.inquery.server.domain.api.param.ShowCreateSequenceParam;
import ai.inquery.server.domain.api.service.SequenceService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.SqlBuilder;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.InqueryContext;
import cn.hutool.core.util.ObjectUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Sequence source management serviceImpl
 *
 */
@Slf4j
@Service
public class SequenceServiceImpl implements SequenceService {
    @Override
    public DataResult<String> showCreateSequence(ShowCreateSequenceParam param) {
        MetaData metaSchema = InqueryContext.getMetaData();
        String ddl = metaSchema.sequenceDDL(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getSequenceName());
        return DataResult.of(ddl);
    }

    @Override
    public ListResult<SimpleSequence> pageQuery(SequencePageQueryParam request) {
        MetaData metaSchema = InqueryContext.getMetaData();
        List<SimpleSequence> sequences = metaSchema.sequences(InqueryContext.getConnection(), request.getDatabaseName(), request.getSchemaName());
        return ListResult.of(sequences);
    }

    @Override
    public ListResult<Sql> buildSql(Sequence oldSequence, Sequence newSequence) {
        SqlBuilder<?> sqlBuilder = InqueryContext.getSqlBuilder();
        List<Sql> sqls = new ArrayList<>();
        if (ObjectUtil.isEmpty(oldSequence)) {
            sqls.add(Sql.builder().sql(sqlBuilder.buildCreateSequenceSql(newSequence)).build());
        } else {
            sqls.add(Sql.builder().sql(sqlBuilder.buildModifySequenceSql(oldSequence, newSequence)).build());
        }
        return ListResult.of(sqls);
    }

    @Override
    public ActionResult drop(DropParam param) {
        DBManage metaSchema = InqueryContext.getDBManage();
        metaSchema.dropSequence(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchema(), param.getName());
        return ActionResult.isSuccess();
    }

    @Override
    public DataResult<Sequence> query(SequenceQueryParam param){
        MetaData metaSchema = InqueryContext.getMetaData();
        Sequence sequences = metaSchema.sequences(InqueryContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), param.getSequenceName());
        return DataResult.of(sequences);
    }
}
