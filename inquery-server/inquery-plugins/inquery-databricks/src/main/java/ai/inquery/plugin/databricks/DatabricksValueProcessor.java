package ai.inquery.plugin.databricks;

import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Databricks-specific value processor.
 * Handles Databricks-specific data types like DECIMAL, NUMERIC, etc.
 */
@Slf4j
public class DatabricksValueProcessor extends DefaultValueProcessor {

    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        try {
            Object obj = dataValue.getObject();
            if (obj == null) {
                return null;
            }
            
            // Handle BigDecimal (common in Databricks for numeric types)
            if (obj instanceof BigDecimal bigDecimal) {
                return bigDecimal.toPlainString();
            }
            
            // Handle Number types
            if (obj instanceof Number number) {
                if (number instanceof Double || number instanceof Float) {
                    return BigDecimal.valueOf(number.doubleValue()).toPlainString();
                }
                return String.valueOf(number);
            }
            
            // Handle Boolean
            if (obj instanceof Boolean bool) {
                return bool.toString();
            }
            
            // Handle byte arrays (binary data)
            if (obj instanceof byte[] bytes) {
                return "[BINARY DATA]";
            }
            
            // Default: use getString
            String strValue = dataValue.getString();
            if (strValue != null) {
                return strValue;
            }
            
            // Last resort: toString
            return obj.toString();
            
        } catch (Exception e) {
            log.warn("Error converting Databricks value: {}", e.getMessage());
            try {
                return dataValue.getString();
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
