package ai.inquery.plugin.clickhouse;


import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class ClickHousePlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"clickhouse.json", DBConfig.class);
    }

    @Override
    public MetaData getMetaData() {
        return new ClickHouseMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new ClickHouseDBManage();
    }
}
