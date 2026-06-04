package ai.inquery.server.domain.core.lineage;

import lombok.Data;
import java.util.Date;

/**
 * Status of lineage auto-detection for a data source.
 */
@Data
public class LineageDetectStatus {

    public enum State {
        IDLE, RUNNING, COMPLETED, FAILED
    }

    private Long dataSourceId;
    private State state = State.IDLE;
    private Date lastRunTime;
    private Date nextRunTime;
    private int nodeCount;
    private int edgeCount;
    private String errorMessage;
}
