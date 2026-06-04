package ai.inquery.plugin.mysql.value.sub;

import ai.inquery.server.tools.common.util.EasyStringUtils;
import ai.inquery.spi.jdbc.DefaultValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.model.SQLDataValue;
import lombok.extern.slf4j.Slf4j;

/**
 * @date: 2024-06-05 0:11
 */
@Slf4j
public class MysqlTextProcessor extends DefaultValueProcessor {


    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return EasyStringUtils.escapeAndQuoteString(dataValue.getValue());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getClobString();
    }

    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return EasyStringUtils.escapeAndQuoteString(dataValue.getClobString());
    }


}
