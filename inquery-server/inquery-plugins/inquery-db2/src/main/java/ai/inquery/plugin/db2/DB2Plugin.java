package ai.inquery.plugin.db2;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class DB2Plugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"db2.json", DBConfig.class);

    }

    @Override
    public MetaData getMetaData() {
        return new DB2MetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new DB2DBManage();
    }
}
