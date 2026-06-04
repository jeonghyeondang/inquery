package ai.inquery.server.domain.core.dbt;

import lombok.Data;

@Data
public class LineageEdge {
    private String sourceId;
    private String targetId;
}
