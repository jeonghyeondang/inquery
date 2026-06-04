package ai.inquery.spi.model;

import lombok.Data;

/**
 * @date: 2024-05-31 16:47
 */
@Data
public class DataType {

    /**
     * Data type name, e.g. "VARCHAR", "INTEGER", "DECIMAL", "DATE".
     * Reflects the exact column type from the database, obtained via {@code ResultSetMetaData.getColumnTypeName()}.
     * Critical for understanding and converting field values, especially DB-specific types (e.g. Oracle NUMBER, MySQL DATETIME).
     */
    private String dataTypeName;

    /**
     * Precision: max character count or total digit count for the type.
     * For numeric types like {@code DECIMAL(5,2)}, precision 5 means total digits (integer + fractional).
     * From {@code ResultSetMetaData.getPrecision()}; helps format numeric values correctly.
     */
    private Integer precision;

    /**
     * Scale (decimal places): only meaningful for numeric types, denotes digits after the decimal point.
     * E.g. in {@code DECIMAL(5,2)}, scale 2 means two decimal places.
     * From {@code ResultSetMetaData.getScale()}; important for precise numeric strings (finance, science).
     */
    private Integer scale;
}
