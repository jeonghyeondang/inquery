package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.plugin.oracle.value.template.OracleDmlValueTemplate;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

/**
 * Functional description
 *
 * @date: 2024-06-05 18:58
 */
public class OracleIntervalYMProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return OracleDmlValueTemplate.wrapIntervalYearToMonth(dataValue.getValue(), dataValue.getPrecision());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getStringValue();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return OracleDmlValueTemplate.wrapIntervalYearToMonth(dataValue.getStringValue(), dataValue.getPrecision());
    }
}
