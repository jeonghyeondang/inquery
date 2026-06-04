package ai.inquery.server.web.api.controller.rdb.data.service;

import ai.inquery.server.domain.api.param.datasource.DatabaseExportDataParam;
import ai.inquery.server.tools.base.wrapper.result.DataResult;

/**
 * @date: 2024-06-08 10:32
 */
public interface DatabaseDataService {

    DataResult<Long> doExportAsync(DatabaseExportDataParam databaseExportDataParam);
}
