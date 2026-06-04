package ai.inquery.server.web.api.controller.rdb.data;

import ai.inquery.server.domain.api.param.datasource.DatabaseExportDataParam;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

public interface DataExportStrategy {


    void doExport(DatabaseExportDataParam databaseExportDataParam, File file) throws IOException, SQLException;
}
