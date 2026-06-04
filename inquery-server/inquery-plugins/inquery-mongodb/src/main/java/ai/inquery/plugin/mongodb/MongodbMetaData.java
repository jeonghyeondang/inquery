package ai.inquery.plugin.mongodb;

import ai.inquery.spi.CommandExecutor;
import ai.inquery.spi.MetaData;
import ai.inquery.spi.jdbc.DefaultMetaService;
import ai.inquery.spi.model.Database;
import com.google.common.collect.Lists;

import java.sql.Connection;
import java.util.List;



public class MongodbMetaData extends DefaultMetaService implements MetaData {

    @Override
    public List<Database> databases(Connection connection) {
        return Lists.newArrayList();
    }

    @Override
    public CommandExecutor getCommandExecutor() {
        return new MongodbCommandExecutor();
    }
}
