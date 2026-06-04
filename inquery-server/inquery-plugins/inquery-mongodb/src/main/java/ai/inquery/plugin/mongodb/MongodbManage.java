package ai.inquery.plugin.mongodb;

import java.sql.Connection;
import java.sql.SQLException;

import ai.inquery.spi.DBManage;
import ai.inquery.spi.jdbc.DefaultDBManage;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import ai.inquery.spi.sql.SQLExecutor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.util.StringUtils;

public class MongodbManage extends DefaultDBManage implements DBManage {
    @Override
    public void connectDatabase(Connection connection, String database) {
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        if (ObjectUtils.anyNull(connectInfo) || StringUtils.isEmpty(connectInfo.getSchemaName())) {
            return;
        }
        String schemaName = connectInfo.getSchemaName();
        if (StringUtils.isEmpty(schemaName)) {
            return;
        }
        try {
            SQLExecutor.getInstance().execute(connection, "use " + schemaName + ";");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void dropTable(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = " db. " + tableName + ".drop();";
        SQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }

}
