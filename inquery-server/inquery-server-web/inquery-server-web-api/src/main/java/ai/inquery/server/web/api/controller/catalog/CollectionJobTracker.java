package ai.inquery.server.web.api.controller.catalog;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks progress of async AI collection jobs.
 * Jobs are stored in memory and auto-cleaned after completion.
 */
@Component
public class CollectionJobTracker {

    private final ConcurrentHashMap<String, JobProgress> jobs = new ConcurrentHashMap<>();

    @Data
    public static class JobProgress {
        private String jobId;
        private String status; // RUNNING, COMPLETED, FAILED
        private int totalTables;
        private int processedTables;
        private int successCount;
        private int failCount;
        private String currentTable;
        private List<String> failedTables = new ArrayList<>();
        private String schemaName;
        private String databaseName;
    }

    public String createJob(String jobId, String databaseName, String schemaName) {
        JobProgress progress = new JobProgress();
        progress.setJobId(jobId);
        progress.setStatus("RUNNING");
        progress.setDatabaseName(databaseName);
        progress.setSchemaName(schemaName);
        jobs.put(jobId, progress);
        return jobId;
    }

    public void setTotalTables(String jobId, int totalTables) {
        JobProgress p = jobs.get(jobId);
        if (p != null) {
            p.setTotalTables(totalTables);
        }
    }

    public void onTableComplete(String jobId, String tableName, boolean success) {
        JobProgress p = jobs.get(jobId);
        if (p != null) {
            p.setProcessedTables(p.getProcessedTables() + 1);
            p.setCurrentTable(tableName);
            if (success) {
                p.setSuccessCount(p.getSuccessCount() + 1);
            } else {
                p.setFailCount(p.getFailCount() + 1);
                p.getFailedTables().add(tableName);
            }
        }
    }

    public void completeJob(String jobId) {
        JobProgress p = jobs.get(jobId);
        if (p != null) {
            p.setStatus("COMPLETED");
        }
        // Schedule cleanup after 5 minutes
        new Thread(() -> {
            try {
                Thread.sleep(5 * 60 * 1000);
            } catch (InterruptedException ignored) {}
            jobs.remove(jobId);
        }).start();
    }

    public void failJob(String jobId, String errorMessage) {
        JobProgress p = jobs.get(jobId);
        if (p != null) {
            p.setStatus("FAILED");
            p.setCurrentTable(errorMessage);
        }
    }

    public JobProgress getProgress(String jobId) {
        return jobs.get(jobId);
    }
}
