package ai.inquery.server.domain.core.dbt;

import lombok.Data;

@Data
public class LineageNode {
    private String uniqueId;
    private String name;
    private String database;
    private String schema;
    /** model, seed, snapshot, source */
    private String resourceType;
    private String description;
    /** table, view, incremental, ephemeral (models only) */
    private String materialization;
    /** Compiled SQL from dbt (models only) */
    private String compiledSql;
}
