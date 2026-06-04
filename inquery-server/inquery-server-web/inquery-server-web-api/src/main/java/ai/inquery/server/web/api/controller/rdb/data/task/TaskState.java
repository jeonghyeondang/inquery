package ai.inquery.server.web.api.controller.rdb.data.task;

import lombok.Builder;
import lombok.Data;

/**
 * @date: 2024-06-10 15:51
 */
@Data
@Builder
public class TaskState {
    private String taskId;
    private String state;
    private int total;
    private int current;


    public String getExportStatus() {
        StringBuilder statusBuilder = new StringBuilder();
        statusBuilder.append("Export status: ").append(state)
                .append(" Export progress: ")
                .append(current).append("/")
                .append(total);
        return statusBuilder.toString();
    }

}
