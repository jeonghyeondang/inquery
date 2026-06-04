package ai.inquery.plugin.oracle.value.sub;

import ai.inquery.server.tools.common.util.EasyStringUtils;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;

import java.util.Objects;

/**
 * @date: 2024-07-07 16:58
 */
public class OracleLongRawProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        String value = dataValue.getValue();
        if (value.startsWith("0x")) {
            // 0xabcd
            return EasyStringUtils.quoteString(value.substring(2));
        } else {
            //example: hello,world
            // TODO: Need to optimize recognition of hexadecimal strings
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean isDigit = (c >= '0' && c <= '9');
                boolean isUpperCaseHex = (c >= 'A' && c <= 'F');
                boolean isLowerCaseHex = (c >= 'a' && c <= 'f');
                if (!isDigit && !isUpperCaseHex && !isLowerCaseHex) {
                    return EasyStringUtils.quoteString(dataValue.getBlobHexString());
                }
            }
            // example: abcd1234
            return EasyStringUtils.quoteString(value);
        }
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getBinaryDataString();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        String blobHexString = dataValue.getBlobHexString();
        if (Objects.isNull(blobHexString)) {
            return "NULL";
        }
        return EasyStringUtils.quoteString(blobHexString);
    }

}
