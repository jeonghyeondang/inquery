package ai.inquery.server.web.api.controller.rdb.vo;

import ai.inquery.spi.model.Database;
import ai.inquery.spi.model.Schema;
import lombok.Data;

import java.util.List;
@Data
public class MetaSchemaVO {
    /**
     * database list
     */
    private List<Database> databases;

    /**
     * schema list
     */
    private List<Schema> schemas;
}
