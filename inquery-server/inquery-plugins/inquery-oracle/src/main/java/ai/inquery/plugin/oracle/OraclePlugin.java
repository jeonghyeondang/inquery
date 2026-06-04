package ai.inquery.plugin.oracle;


import ai.inquery.spi.DBManage;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.Plugin;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.FileUtils;

public class OraclePlugin implements Plugin {
    @Override
    public DBConfig getDBConfig() {
        return FileUtils.readJsonValue(this.getClass(),"oracle.json", DBConfig.class);

    }

    @Override
    public MetaData getMetaData() {
        return new OracleMetaData();
    }

    @Override
    public DBManage getDBManage() {
        return new OracleDBManage();
    }
}
