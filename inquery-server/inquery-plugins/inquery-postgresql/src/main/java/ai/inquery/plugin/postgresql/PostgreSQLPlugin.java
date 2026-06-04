package ai.inquery.plugin.postgresql;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class PostgreSQLPlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"pg.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new PostgreSQLMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new PostgreSQLDBManage();
    }
}
