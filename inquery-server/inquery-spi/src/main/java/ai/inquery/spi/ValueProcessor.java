package ai.inquery.spi;


import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

public interface ValueProcessor {

    /**
     * Converts a given value into a format suitable for use in an SQL statement
     * <br>
     * Example:
     * <br>
     * Input oracle DATE : '2024-05-29 11:35:20.0'
     * <br>
     * Output for Oracle DATE: TO_DATE('2024-05-29 14:25:00', 'SYYYY-MM-DD HH24:MI:SS')
     */
    String getSqlValueString(SQLDataValue dataValue);


    /**
     * Converts a JDBC data value to a string suitable for frontend display.
     * Handles numbers, dates, strings, and null values, ensuring data is well-formatted and readable in the UI.
     *
     * @param dataValue Composite of ResultSetMetaData, ResultSet, and columnIndex for retrieving the value
     * @return Formatted string for frontend display (e.g. dates as "YYYY-MM-DD")
     */
    String getJdbcValue(JDBCDataValue dataValue);

    /**
     * Converts a JDBC ResultSet value into a string suitable for DML statements.
     *
     * @param dataValue Data value retrieved from JDBC, used to prepare DML values
     * @return Formatted string suitable for direct use in DML (insert/update)
     */
    String getJdbcSqlValueString(JDBCDataValue dataValue);
}
