package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

/**
 * Functional description
 *
 * @date: 2024-06-05 20:00
 */
public class OracleNumberProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return dataValue.getValue();
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getBigDecimalString();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return dataValue.getBigDecimalString();
    }
}
