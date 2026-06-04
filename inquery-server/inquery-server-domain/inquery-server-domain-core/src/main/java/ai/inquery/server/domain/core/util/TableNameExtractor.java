package ai.inquery.server.domain.core.util;

/**
 * Table name extractor utility class
 * 
 * Extracts the actual table name from a full table name (including schema).
 * Example: PROD.RAW.EARN_CURRENCY -> EARN_CURRENCY
 */
public class TableNameExtractor {
    
    /**
     * Extract the actual table name from a full table name
     * 
     * @param fullTableName Full table name (may include schema)
     * @return Actual table name (last part separated by dots)
     */
    public static String extract(String fullTableName) {
        if (fullTableName == null || fullTableName.isEmpty()) {
            return fullTableName;
        }
        
        String[] parts = fullTableName.split("\\.");
        return parts[parts.length - 1];
    }
}







