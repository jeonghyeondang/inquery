package ai.inquery.plugin.sqlite;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class SqlitePlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"sqlite.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new SqliteMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new SqliteDBManage();
    }
}
