package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.plugin.oracle.value.template.OracleDmlValueTemplate;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

/**
 * Oracle timestamp with timezone value processor
 *
 * @date: 2024/06/05 17:32
 */
public class OracleTimeStampTZProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return wrap(dataValue.getValue(), dataValue.getScale());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        String timeStampString = dataValue.getStringValue();
        int scale = dataValue.getScale();
        int lastSpaceIndex = timeStampString.lastIndexOf(" ");
        int lastDotIndex = timeStampString.indexOf(".");
        int nanosLength = lastSpaceIndex - lastDotIndex - 1;
        if (scale == 0) {
            return timeStampString.substring(0, lastDotIndex) + timeStampString.substring(lastDotIndex + 2);
        } else if (nanosLength < scale) {
            // Calculate the number of zeros to add
            int zerosToAdd = scale - nanosLength;
            StringBuilder sb = new StringBuilder(timeStampString);
            for (int i = 0; i < zerosToAdd; i++) {
                sb.insert(lastSpaceIndex, '0');
            }
            return sb.toString();

        }
        return timeStampString;
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return wrap(convertJDBCValueByType(dataValue), dataValue.getScale());
    }

    private String wrap(String value, int scale) {
        if (scale == 0) {
            return OracleDmlValueTemplate.wrapTimestampTzWithOutNanos(value);
        }
        return OracleDmlValueTemplate.wrapTimestampTz(value, scale);
    }
}
