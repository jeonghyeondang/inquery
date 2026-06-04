
package ai.inquery.spi.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 */
@Slf4j
public class ResultSetUtils {


    public static List<String> getRsHeader(ResultSet rs) {
        try {
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            List<String> headerList = Lists.newArrayListWithExpectedSize(col);
            for (int i = 1; i <= col; i++) {
                headerList.add(getColumnName(resultSetMetaData, i));
            }
            return headerList;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param rs
     * @param clazz
     * @return
     * @param <T>
     */
    public static <T> List<T> toObjectList(ResultSet rs, Class<T> clazz) {
        try {
            if (rs == null || clazz == null) {
                return Lists.newArrayList();
            }
            List<T> list = Lists.newArrayList();
            ResultSetMetaData rsMetaData = rs.getMetaData();
            int col = rsMetaData.getColumnCount();
            List<String> headerList = getRsHeader(rs);
            ObjectMapper mapper = new ObjectMapper();
            mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 1; i <= col; i++) {
                    map.put(headerList.get(i-1), rs.getObject(i));
                }
                T obj = mapper.convertValue(map, clazz);

                list.add(obj);
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    public static String getColumnName(ResultSetMetaData resultSetMetaData, int column) throws SQLException {
        String columnLabel = resultSetMetaData.getColumnLabel(column);
        if (columnLabel != null) {
            return columnLabel;
        }
        return resultSetMetaData.getColumnName(column);
    }

    public static String getColumnDataTypeName(ResultSetMetaData resultSetMetaData, int columnIndex) {
        try {
            return resultSetMetaData.getColumnTypeName(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getColumnPrecision(ResultSetMetaData resultSetMetaData, int columnIndex){
        try {
            return resultSetMetaData.getPrecision(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getColumnScale(ResultSetMetaData resultSetMetaData, int columnIndex){
        try {
            return resultSetMetaData.getScale(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getString(ResultSet rs, int columnIndex){
        try {
            Object obj = rs.getObject(columnIndex);
            if (obj == null) {
                return null;
            }
            if(obj instanceof String){
                return (String) obj;
            }else if (obj instanceof BigDecimal bigDecimal) {
                return bigDecimal.toPlainString();
            } else if (obj instanceof Double d) {
                return BigDecimal.valueOf(d).toPlainString();
            } else if (obj instanceof Float f) {
                return BigDecimal.valueOf(f).toPlainString();
            } else if (obj instanceof Clob) {
                return largeString(rs, columnIndex);
            } else if (obj instanceof byte[]) {
                return largeString(rs, columnIndex);
            } else if (obj instanceof Blob blob) {
                return largeStringBlob(blob);
            } else if (obj instanceof Timestamp || obj instanceof LocalDateTime) {
                return largeTime(obj);
            } else if (obj instanceof SQLXML){
                return ((SQLXML) obj).getString();
            } else if (obj instanceof Struct struct) {
                return formatStruct(struct);
            } else if (obj instanceof Array array) {
                return formatArray(array);
            } else if (obj instanceof Map<?, ?> map) {
                return formatMap(map);
            } else if (obj instanceof Iterable<?> iterable) {
                return formatIterable(iterable);
            } else {
                return obj.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse number:{},", columnIndex, e);
            try {
                return rs.getString(columnIndex);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private static String formatStruct(Struct struct) {
        try {
            Object[] attrs = struct.getAttributes();
            if (attrs == null) return null;
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < attrs.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatComplexValue(attrs[i]));
            }
            sb.append("}");
            return sb.toString();
        } catch (SQLException e) {
            return struct.toString();
        }
    }

    private static String formatArray(Array array) {
        try {
            Object raw = array.getArray();
            if (raw instanceof Object[] elements) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < elements.length; i++) {
                    if (i > 0) sb.append(", ");
                    if (i >= 20) {
                        sb.append("...(").append(elements.length - 20).append(" more)");
                        break;
                    }
                    sb.append(formatComplexValue(elements[i]));
                }
                sb.append("]");
                return sb.toString();
            }
            return raw != null ? raw.toString() : null;
        } catch (SQLException e) {
            return array.toString();
        }
    }

    private static String formatMap(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i > 0) sb.append(", ");
            sb.append(entry.getKey()).append(": ").append(formatComplexValue(entry.getValue()));
            i++;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String formatIterable(Iterable<?> iterable) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Object item : iterable) {
            if (i > 0) sb.append(", ");
            if (i >= 20) {
                sb.append("...");
                break;
            }
            sb.append(formatComplexValue(item));
            i++;
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatComplexValue(Object val) {
        if (val == null) return "null";
        if (val instanceof Struct s) return formatStruct(s);
        if (val instanceof Array a) return formatArray(a);
        if (val instanceof Map<?, ?> m) return formatMap(m);
        if (val instanceof Iterable<?> it) return formatIterable(it);
        if (val instanceof String s) return s;
        if (val instanceof BigDecimal bd) return bd.toPlainString();
        return val.toString();
    }

    private static String largeStringBlob(Blob blob) throws SQLException {
        if (blob == null) {
            return null;
        }
        int length = Math.toIntExact(blob.length());
        byte[] data = blob.getBytes(1, length);
        String result = new String(data, StandardCharsets.UTF_8);
        return result;
    }

    private static String largeTime(Object obj) throws SQLException {
        Object timeField = obj; // Assuming a time field of type Object

        LocalDateTime localDateTime;

        if (obj instanceof Timestamp) {
            // Convert a time field of type Object to a LocalDateTime object
            localDateTime = ((Timestamp) timeField).toLocalDateTime();
        } else if(obj instanceof  LocalDateTime){
            localDateTime = (LocalDateTime) timeField;
        } else {
            try {
                localDateTime = LocalDateTime.parse(timeField.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            }catch (Exception e){
                localDateTime = LocalDateTime.parse(timeField.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            }
        }
        // Create a DateTimeFormatter instance and specify the output date and time format
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // Format date time
        String formattedDateTime = dtf.format(localDateTime);
        return formattedDateTime;
    }

    private static String largeString(ResultSet rs, int index) throws SQLException {
        String result = rs.getString(index);
        if (result == null) {
            return null;

        }
        return result;
    }

    public static InputStream getBinaryStream(ResultSet rs, int columnIndex) {
        try {
            return rs.getBinaryStream(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] getBytes(ResultSet rs, int columnIndex) {
        try {
            return rs.getBytes(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean getBoolean(ResultSet rs, int columnIndex) {
        try {
            return rs.getBoolean(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getInt(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getInt(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Date getDate(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getDate(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Timestamp getTimestamp(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getTimestamp(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Clob getClob(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getClob(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Blob getBlob(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getBlob(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static BigDecimal getBigDecimal(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getBigDecimal(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getStringValue(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getString(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}