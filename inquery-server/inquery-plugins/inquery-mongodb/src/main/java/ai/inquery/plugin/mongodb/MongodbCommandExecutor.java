package ai.inquery.plugin.mongodb;

import ai.inquery.spi.model.Command;
import ai.inquery.spi.model.ExecuteResult;
import ai.inquery.spi.sql.SQLExecutor;

import java.util.List;

public class MongodbCommandExecutor extends SQLExecutor {

    @Override
    public List<ExecuteResult> executeSelectTable(Command command) {
        String sql = "db." + command.getTableName() + ".find()";
        command.setScript(sql);
        return execute(command);
    }
}
