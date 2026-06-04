package ai.inquery.plugin.mysql;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class MysqlPlugin implements Plugin {

    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"mysql.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new MysqlMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new MysqlDBManage();
    }
}
