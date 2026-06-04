package ai.inquery.server.domain.core.dbt;

import lombok.Data;
import java.util.List;

@Data
public class LineageGraph {
    private List<LineageNode> nodes;
    private List<LineageEdge> edges;
}
