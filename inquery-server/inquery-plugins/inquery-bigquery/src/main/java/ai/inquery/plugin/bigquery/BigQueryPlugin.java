package ai.inquery.plugin.bigquery;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class BigQueryPlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(), "bigquery.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new BigQueryMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new BigQueryDBManage();
    }
}

