package ai.inquery.plugin.mysql.value.template;

/**
 * @date: 2024-06-01 13:31
 */
public class MysqlDmlValueTemplate {

    public static final String GEOMETRY_TEMPLATE = "ST_GeomFromText('%s')";
    public static final String BIT_TEMPLATE = "b'%s'";
    public static final String HEX_TEMPLATE = "0x%s";


    public static String wrapGeometry(String value) {
        return String.format(GEOMETRY_TEMPLATE, value);
    }

    public static String wrapBit(String value) {
        return String.format(BIT_TEMPLATE, value);
    }

    public static String wrapHex(String value) {
        return String.format(HEX_TEMPLATE, value);
    }
}
