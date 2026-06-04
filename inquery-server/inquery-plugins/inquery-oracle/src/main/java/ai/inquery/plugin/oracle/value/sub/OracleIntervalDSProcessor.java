package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.plugin.oracle.value.template.OracleDmlValueTemplate;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

/**
 * @date: 2024-06-05 18:56
 */
public class OracleIntervalDSProcessor extends DefaultValueProcessor {


    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return OracleDmlValueTemplate.wrapIntervalDayToSecond(dataValue.getValue(), dataValue.getPrecision(), dataValue.getScale());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getStringValue();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return OracleDmlValueTemplate.wrapIntervalDayToSecond(convertJDBCValueByType(dataValue), dataValue.getPrecision(), dataValue.getScale());
    }

}
