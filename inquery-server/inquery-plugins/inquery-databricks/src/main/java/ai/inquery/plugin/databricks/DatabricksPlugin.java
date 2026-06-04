package ai.inquery.plugin.databricks;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

/**
 * Databricks database plugin implementation.
 * Supports Unity Catalog with catalog.schema.table hierarchy.
 */
public class DatabricksPlugin implements Plugin {
    
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(), "databricks.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new DatabricksMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new DatabricksDBManage();
    }
}
