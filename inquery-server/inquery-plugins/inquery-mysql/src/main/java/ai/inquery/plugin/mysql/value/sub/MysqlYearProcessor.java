package ai.inquery.plugin.mysql.value.sub;

import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

/**
 * Functional description
 *
 * @date: 2024-06-01 12:57
 */
public class MysqlYearProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return dataValue.getValue();
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return new String(dataValue.getBytes());
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return new String(dataValue.getBytes());
    }
}
