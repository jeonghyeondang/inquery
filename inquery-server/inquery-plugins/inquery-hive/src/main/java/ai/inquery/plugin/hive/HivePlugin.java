package ai.inquery.plugin.hive;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class HivePlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"hive.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new HiveMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new HiveDBManage();
    }
}
