package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.server.tools.common.util.EasyStringUtils;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

/**
 * @date: 2024-06-04 17:06
 */
public class OracleClobProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return EasyStringUtils.escapeAndQuoteString(dataValue.getValue());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getClobString();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return EasyStringUtils.escapeAndQuoteString(dataValue.getClobString());
    }
}
