package ai.inquery.server.web.api.controller.rdb.request;

import java.util.List;

import ai.inquery.spi.enums.IndexTypeEnum;

import ai.inquery.spi.model.TableIndexColumn;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * index
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IndexRequest {

    /**
     * Index name
     */
    private String name;

    /**
     * all types
     *
     * @see IndexTypeEnum
     */
    private String type;

    /**
     * Comment
     */
    private String comment;

    /**
     * Columns included in the index
     */
    private List<TableIndexColumn> columnList;


    private String editStatus;

}
