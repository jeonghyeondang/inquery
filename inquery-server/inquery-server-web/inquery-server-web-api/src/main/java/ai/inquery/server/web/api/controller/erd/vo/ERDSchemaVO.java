package ai.inquery.server.web.api.controller.erd.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema information for ERD visualization
 * Contains all tables with their columns and relationships
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ERDSchemaVO {

    /**
     * Schema name
     */
    private String name;

    /**
     * Database name
     */
    private String databaseName;

    /**
     * List of tables in this schema
     */
    private List<ERDTableVO> tables;
}



