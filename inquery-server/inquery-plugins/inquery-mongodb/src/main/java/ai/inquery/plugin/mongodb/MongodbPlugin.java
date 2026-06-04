package ai.inquery.plugin.mongodb;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class MongodbPlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"mongodb.json", DBConfig.class);

    }

    @Override
    public MetaData getMetaData() {
        return new MongodbMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new MongodbManage();
    }
}
