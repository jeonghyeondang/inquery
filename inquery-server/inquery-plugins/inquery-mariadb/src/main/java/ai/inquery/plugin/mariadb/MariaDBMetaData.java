package ai.inquery.plugin.mariadb;


import ai.inquery.plugin.mariadb.value.MariaDBValueProcessor;
import ai.inquery.plugin.mysql.MysqlMetaData;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.ValueProcessor;

public class MariaDBMetaData extends MysqlMetaData implements MetaData {

    @Override
    public ValueProcessor getValueProcessor() {
        return new MariaDBValueProcessor();
    }
}
