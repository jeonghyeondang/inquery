package ai.inquery.plugin.snowflake;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class SnowflakePlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"snowflake.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new SnowflakeMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new SnowflakeDBManage();
    }
}



