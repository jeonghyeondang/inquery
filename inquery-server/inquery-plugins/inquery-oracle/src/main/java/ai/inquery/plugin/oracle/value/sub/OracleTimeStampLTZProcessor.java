package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.plugin.oracle.value.template.OracleDmlValueTemplate;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @date: 2024/07/05 16:19
 */
public class OracleTimeStampLTZProcessor extends DefaultValueProcessor {


    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return wrap(dataValue.getValue(), dataValue.getScale());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        Timestamp timestamp = dataValue.getTimestamp();
        int scale = dataValue.getScale();
        LocalDateTime localDateTime = timestamp.toLocalDateTime();
        StringBuilder templateBuilder = new StringBuilder("yyyy-MM-dd HH:mm:ss");
        if (scale != 0) {
            templateBuilder.append(".");
            templateBuilder.append("S".repeat(scale));
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(templateBuilder.toString());
        return localDateTime.format(formatter);
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        Timestamp timestamp = dataValue.getTimestamp();
        int scale = dataValue.getScale();
        // Convert Timestamp to Instant object
        Instant instant = timestamp.toInstant();
        // Convert Instant to ZonedDateTime in UTC timezone
        ZonedDateTime utcZonedDateTime = instant.atZone(ZoneId.of("UTC"));
        StringBuilder templateBuilder = new StringBuilder("yyyy-MM-dd HH:mm:ss");
        if (scale != 0) {
            templateBuilder.append(".");
            templateBuilder.append("S".repeat(scale));
        }
        // Define date-time formatter
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(templateBuilder.toString());
        // Format ZonedDateTime in UTC timezone
        String formattedUtcTime = utcZonedDateTime.format(formatter);
        return wrap(formattedUtcTime, scale);
    }

    private String wrap(String value, int scale) {
        if (scale == 0) {
            return OracleDmlValueTemplate.wrapDate(value);
        }
        return OracleDmlValueTemplate.wrapTimestamp(value, scale);
    }
}
