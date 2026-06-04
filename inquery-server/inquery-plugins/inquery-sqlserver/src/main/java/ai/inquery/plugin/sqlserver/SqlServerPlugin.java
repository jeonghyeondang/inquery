package ai.inquery.plugin.sqlserver;


import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class SqlServerPlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"sqlserver.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new SqlServerMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new SqlServerDBManage();
    }
}
