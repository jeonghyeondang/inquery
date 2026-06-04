package ai.inquery.plugin.presto;

import ai.inquery.spi.MetaData;
import ai.inquery.spi.jdbc.DefaultMetaService;

public class PrestoMetaData extends DefaultMetaService implements MetaData {
    public String tableDDL(String databaseName, String schemaName,String tableName) {
        return "";
    }
}
