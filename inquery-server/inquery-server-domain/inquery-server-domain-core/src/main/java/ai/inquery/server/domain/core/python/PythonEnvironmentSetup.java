package ai.inquery.server.domain.core.python;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages Python environment setup for data analysis tools.
 * On startup, checks for Python, creates venv, installs packages.
 * Does not block server startup - runs async.
 */
@Slf4j
@Component
public class PythonEnvironmentSetup {

    private static final String INQUERY_HOME = System.getProperty("user.home") + "/.inquery";
    private static final String VENV_DIR = INQUERY_HOME + "/python-env";
    private static final String WORKSPACE_DIR = INQUERY_HOME + "/python-workspace";

    private static final List<String> REQUIRED_PACKAGES = List.of(
            "pandas", "matplotlib", "numpy", "scikit-learn"
    );

    @Getter
    private volatile boolean ready = false;

    @Getter
    private volatile String pythonPath;

    @Getter
    private volatile String errorMessage;

    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(this::setup);
    }

    private void setup() {
        try {
            log.info("Setting up Python environment...");

            // 1. Find Python
            String systemPython = findPython();
            if (systemPython == null) {
                errorMessage = "Python 3 not found. Please install Python 3.9+.";
                log.warn(errorMessage);
                return;
            }
            log.info("Found Python: {}", systemPython);

            // 2. Create directories
            Files.createDirectories(Path.of(INQUERY_HOME));
            Files.createDirectories(Path.of(WORKSPACE_DIR));

            // 3. Create venv if not exists
            Path venvPath = Path.of(VENV_DIR);
            String venvPython = VENV_DIR + "/bin/python3";

            if (!Files.exists(venvPath)) {
                log.info("Creating Python venv at {}...", VENV_DIR);
                int exitCode = runCommand(List.of(systemPython, "-m", "venv", VENV_DIR), 60);
                if (exitCode != 0) {
                    errorMessage = "Failed to create Python venv";
                    log.error(errorMessage);
                    return;
                }
            }

            if (!Files.exists(Path.of(venvPython))) {
                errorMessage = "Python venv created but python3 binary not found at: " + venvPython;
                log.error(errorMessage);
                return;
            }

            // 4. Install required packages
            log.info("Installing Python packages: {}", REQUIRED_PACKAGES);
            List<String> pipCmd = new java.util.ArrayList<>();
            pipCmd.add(venvPython);
            pipCmd.add("-m");
            pipCmd.add("pip");
            pipCmd.add("install");
            pipCmd.add("--quiet");
            pipCmd.addAll(REQUIRED_PACKAGES);

            int pipExit = runCommand(pipCmd, 120);
            if (pipExit != 0) {
                log.warn("Some Python packages may have failed to install (exit code: {})", pipExit);
            }

            this.pythonPath = venvPython;
            this.ready = true;
            log.info("Python environment ready: {}", venvPython);

        } catch (Exception e) {
            errorMessage = "Python setup failed: " + e.getMessage();
            log.error(errorMessage, e);
        }
    }

    private String findPython() {
        for (String cmd : List.of("python3", "python")) {
            try {
                Process process = new ProcessBuilder("which", cmd)
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String path = reader.readLine();
                        if (path != null && !path.isBlank()) {
                            return path.trim();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private int runCommand(List<String> command, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        // Consume output to prevent blocking
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[Python setup] {}", line);
                }
            } catch (Exception ignored) {
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    public String getWorkspaceDir() {
        return WORKSPACE_DIR;
    }
}
