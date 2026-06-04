package ai.inquery.spi.sql;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface ResultSetFunction<R> {

    R apply(ResultSet t) throws SQLException;
}
