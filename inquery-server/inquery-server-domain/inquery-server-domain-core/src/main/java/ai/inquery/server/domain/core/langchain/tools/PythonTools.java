package ai.inquery.server.domain.core.langchain.tools;

import ai.inquery.server.domain.core.python.PythonEnvironmentSetup;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Python execution tool for data analysis.
 * Runs Python code via ProcessBuilder using the managed venv.
 * Pre-installed packages: pandas, matplotlib, numpy, scikit-learn.
 */
@Slf4j
public class PythonTools {

    private final PythonEnvironmentSetup pythonEnv;
    private static final int TIMEOUT_SECONDS = 60;

    public PythonTools(PythonEnvironmentSetup pythonEnv) {
        this.pythonEnv = pythonEnv;
    }

    @Tool("Execute Python code for data analysis, statistical computation, or chart generation. "
            + "Use when: query results have 100+ rows, statistical analysis is needed (correlation, regression, clustering), "
            + "predictions are requested, complex pattern detection, or advanced visualization is needed. "
            + "Pre-installed: pandas, matplotlib, numpy, scikit-learn. "
            + "Print results to stdout. Save charts as PNG to the output directory (available as OUTPUT_DIR variable). "
            + "Input data is available as a pandas DataFrame named 'df'.")
    public String executePython(
            @P("Python code to execute") String code,
            @P("CSV-formatted data to analyze. Will be loaded as pandas DataFrame 'df'") String inputData
    ) {
        if (!pythonEnv.isReady()) {
            return "Error: Python environment is not ready. " +
                    (pythonEnv.getErrorMessage() != null ? pythonEnv.getErrorMessage() : "Still initializing...");
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        Path workDir = Path.of(pythonEnv.getWorkspaceDir(), requestId);

        try {
            Files.createDirectories(workDir);
            Path outputDir = workDir.resolve("output");
            Files.createDirectories(outputDir);

            // Write input data
            Path dataFile = workDir.resolve("input_data.csv");
            if (inputData != null && !inputData.isBlank()) {
                Files.writeString(dataFile, inputData);
            }

            // Wrap user code with boilerplate
            String wrappedCode = buildWrappedCode(code, dataFile.toString(), outputDir.toString());
            Path scriptFile = workDir.resolve("script.py");
            Files.writeString(scriptFile, wrappedCode);

            // Execute
            log.info("Executing Python script: requestId={}", requestId);
            ProcessBuilder pb = new ProcessBuilder(pythonEnv.getPythonPath(), scriptFile.toString())
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Python execution timed out after " + TIMEOUT_SECONDS + " seconds";
            }

            int exitCode = process.exitValue();
            String stdout = output.toString().trim();

            if (exitCode != 0) {
                log.warn("Python script failed (exit {}): {}", exitCode, stdout);
                return "Python execution error (exit code " + exitCode + "):\n" + stdout;
            }

            // Collect results: stdout + any generated charts
            StringBuilder result = new StringBuilder();
            if (!stdout.isEmpty()) {
                result.append(stdout);
            }

            // Check for generated chart images
            if (Files.exists(outputDir)) {
                var chartFiles = Files.list(outputDir)
                        .filter(p -> p.toString().endsWith(".png"))
                        .collect(Collectors.toList());

                if (!chartFiles.isEmpty()) {
                    result.append("\n\n[Charts generated: ").append(chartFiles.size()).append(" file(s)]");
                    for (Path chartFile : chartFiles) {
                        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(chartFile));
                        result.append("\n[CHART_IMAGE:").append(base64).append("]");
                    }
                }
            }

            log.info("Python execution completed: requestId={}, output={} chars", requestId, result.length());
            return result.toString();

        } catch (Exception e) {
            log.error("Python execution failed: requestId={}", requestId, e);
            return "Python execution failed: " + e.getMessage();
        } finally {
            // Clean up work directory
            try {
                deleteRecursive(workDir);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Generate and execute Python statistics code for large result sets.
     * LLM generates statistics-only code; full data is already on disk as CSV.
     * No charts — purpose is to summarize data that's too large for LLM token limits.
     */
    public String executePythonWithDataFile(String prompt, String dataFilePath,
                                            dev.langchain4j.model.chat.ChatModel chatModel) {
        String codeGenPrompt = prompt + "\n\n"
                + "Generate ONLY Python code (no explanation, no markdown fences).\n"
                + "The data is already loaded as pandas DataFrame 'df'.\n"
                + "Your goal: produce a STATISTICAL SUMMARY that another LLM can use to answer the user's question.\n"
                + "Print all results to stdout. Do NOT create charts or save files.\n"
                + "Include: counts, averages, min/max, percentages, top-N rankings, trends, groupby summaries — "
                + "whatever is relevant to the user's question.";

        String generatedCode = chatModel.chat(codeGenPrompt);

        // Strip markdown code fences if present
        generatedCode = generatedCode.replaceAll("(?s)^```(?:python)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "").trim();

        return executePythonWithFile(generatedCode, dataFilePath);
    }

    /**
     * Execute Python code with a specific data file path (bypasses inputData writing).
     */
    private String executePythonWithFile(String code, String dataFilePath) {
        if (!pythonEnv.isReady()) {
            return "Error: Python environment is not ready. " +
                    (pythonEnv.getErrorMessage() != null ? pythonEnv.getErrorMessage() : "Still initializing...");
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        Path workDir = Path.of(pythonEnv.getWorkspaceDir(), requestId);

        try {
            Files.createDirectories(workDir);
            Path outputDir = workDir.resolve("output");
            Files.createDirectories(outputDir);

            String wrappedCode = buildWrappedCode(code, dataFilePath, outputDir.toString());
            Path scriptFile = workDir.resolve("script.py");
            Files.writeString(scriptFile, wrappedCode);

            log.info("Executing Python script (pre-written data): requestId={}", requestId);
            ProcessBuilder pb = new ProcessBuilder(pythonEnv.getPythonPath(), scriptFile.toString())
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Python execution timed out after " + TIMEOUT_SECONDS + " seconds";
            }

            int exitCode = process.exitValue();
            String stdout = output.toString().trim();

            if (exitCode != 0) {
                log.warn("Python script failed (exit {}): {}", exitCode, stdout);
                return "Python execution error (exit code " + exitCode + "):\n" + stdout;
            }

            StringBuilder result = new StringBuilder();
            if (!stdout.isEmpty()) {
                result.append(stdout);
            }

            if (Files.exists(outputDir)) {
                var chartFiles = Files.list(outputDir)
                        .filter(p -> p.toString().endsWith(".png"))
                        .collect(Collectors.toList());

                if (!chartFiles.isEmpty()) {
                    result.append("\n\n[Charts generated: ").append(chartFiles.size()).append(" file(s)]");
                    for (Path chartFile : chartFiles) {
                        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(chartFile));
                        result.append("\n[CHART_IMAGE:").append(base64).append("]");
                    }
                }
            }

            log.info("Python execution completed: requestId={}, output={} chars", requestId, result.length());
            return result.toString();

        } catch (Exception e) {
            log.error("Python execution failed: requestId={}", requestId, e);
            return "Python execution failed: " + e.getMessage();
        } finally {
            try { deleteRecursive(workDir); } catch (Exception ignored) {}
        }
    }

    private String buildWrappedCode(String userCode, String dataFilePath, String outputDir) {
        return """
                import pandas as pd
                import numpy as np
                import matplotlib
                matplotlib.use('Agg')  # Non-interactive backend
                import matplotlib.pyplot as plt
                import warnings
                warnings.filterwarnings('ignore')

                # Load input data
                OUTPUT_DIR = '%s'
                try:
                    df = pd.read_csv('%s')
                except Exception:
                    df = pd.DataFrame()

                # User code
                %s

                # Save any open matplotlib figures
                for i, fig_num in enumerate(plt.get_fignums()):
                    plt.figure(fig_num)
                    plt.savefig(f'{OUTPUT_DIR}/chart_{i}.png', dpi=150, bbox_inches='tight')
                    plt.close(fig_num)
                """.formatted(outputDir, dataFilePath, userCode);
    }

    private void deleteRecursive(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.collect(Collectors.toList())) {
                    deleteRecursive(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
