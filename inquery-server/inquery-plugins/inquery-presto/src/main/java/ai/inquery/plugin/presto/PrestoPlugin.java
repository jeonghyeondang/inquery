package ai.inquery.plugin.presto;


import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class PrestoPlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"presto.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new PrestoMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new PrestoDBManage();
    }
}
