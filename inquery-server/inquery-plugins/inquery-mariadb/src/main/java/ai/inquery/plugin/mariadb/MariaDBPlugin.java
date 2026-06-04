package ai.inquery.plugin.mariadb;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class MariaDBPlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"mariadb.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new MariaDBMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new MariaDBManage();
    }
}
