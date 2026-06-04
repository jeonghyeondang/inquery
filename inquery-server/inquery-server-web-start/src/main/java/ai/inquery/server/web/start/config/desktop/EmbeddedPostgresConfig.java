package ai.inquery.server.web.start.config.desktop;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.stream.Stream;

@Configuration
@ConditionalOnProperty(name = "inquery.desktop.embedded-postgres.enabled", havingValue = "true")
@Slf4j
public class EmbeddedPostgresConfig {

    private EmbeddedPostgres embeddedPostgres;

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) throws IOException {
        Path dataDir = getDataDirectory();
        Path pgInstallDir = getPgInstallDirectory();

        log.info("Starting embedded PostgreSQL, data directory: {}", dataDir);

        embeddedPostgres = EmbeddedPostgres.builder()
                .setPort(15432)
                .setDataDirectory(dataDir)
                .setOverrideWorkingDirectory(pgInstallDir.toFile())
                .setCleanDataDirectory(false)
                .start();

        log.info("Embedded PostgreSQL started on port 15432");

        DataSource adminDs = embeddedPostgres.getPostgresDatabase();
        installPgVectorExtension(adminDs);

        try (var conn = adminDs.getConnection();
             var stmt = conn.createStatement()) {
            var roleRs = stmt.executeQuery(
                    "SELECT 1 FROM pg_roles WHERE rolname = 'inquery'");
            if (!roleRs.next()) {
                stmt.execute("CREATE ROLE inquery WITH LOGIN PASSWORD 'inquery' SUPERUSER");
                log.info("Created role: inquery");
            }
            roleRs.close();

            var dbRs = stmt.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = 'inquery_desktop'");
            if (!dbRs.next()) {
                stmt.execute("CREATE DATABASE inquery_desktop OWNER inquery");
                log.info("Created database: inquery_desktop");
            }
            dbRs.close();
        } catch (Exception e) {
            log.warn("Database setup failed: {}", e.getMessage());
        }

        return embeddedPostgres.getDatabase("inquery", "inquery_desktop");
    }

    /**
     * Queries the running PG instance for its actual library/extension directories,
     * then copies pre-compiled pgvector files there.
     */
    private void installPgVectorExtension(DataSource adminDs) {
        String pgvectorDir = resolvePgVectorDir();
        if (pgvectorDir == null || pgvectorDir.isBlank()) {
            log.warn("pgvector resources not found — vector search will be unavailable until the extension is installed");
            return;
        }

        Path pgvectorPath = Paths.get(pgvectorDir);
        if (!Files.exists(pgvectorPath)) {
            log.warn("pgvector resources not found at: {}", pgvectorDir);
            return;
        }

        log.info("Using pgvector resources from: {}", pgvectorPath.toAbsolutePath());

        try (var conn = adminDs.getConnection(); var stmt = conn.createStatement()) {
            String pkgLibDir = queryPgConfig(stmt, "PKGLIBDIR");
            String shareDir = queryPgConfig(stmt, "SHAREDIR");

            if (pkgLibDir == null || shareDir == null) {
                log.warn("Could not determine PG library paths from pg_config");
                return;
            }

            Path targetLibDir = Paths.get(pkgLibDir);
            Path targetExtDir = Paths.get(shareDir, "extension");
            Files.createDirectories(targetExtDir);

            log.info("PG PKGLIBDIR: {}", targetLibDir);
            log.info("PG extension dir: {}", targetExtDir);

            copyPgVectorLibrary(pgvectorPath.resolve("lib"), targetLibDir);
            copyPgVectorExtensionFiles(pgvectorPath.resolve("share").resolve("extension"), targetExtDir);

            log.info("pgvector extension installed successfully");

            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            log.info("pgvector extension activated");
        } catch (Exception e) {
            log.warn("pgvector extension installation failed: {}", e.getMessage());
        }
    }

    /**
     * Resolve bundled pgvector native files. Desktop passes
     * {@code -Dinquery.desktop.pgvector.dir=...}; dev mode falls back to
     * well-known locations so local {@code spring-boot:run} works without extra flags.
     */
    private String resolvePgVectorDir() {
        String configured = System.getProperty("inquery.desktop.pgvector.dir");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        Path homeBundle = Paths.get(System.getProperty("user.home"), ".inquery", "pgvector");
        if (Files.isDirectory(homeBundle)) {
            return homeBundle.toString();
        }

        String userDir = System.getProperty("user.dir", ".");
        Path[] candidates = {
            Paths.get(userDir, "inquery-client-svelte", "src-tauri", "resources", "pgvector"),
            Paths.get(userDir, "..", "inquery-client-svelte", "src-tauri", "resources", "pgvector"),
            Paths.get(userDir, "..", "..", "inquery-client-svelte", "src-tauri", "resources", "pgvector"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private String queryPgConfig(java.sql.Statement stmt, String name) {
        try {
            var rs = stmt.executeQuery("SELECT setting FROM pg_config WHERE name = '" + name + "'");
            if (rs.next()) return rs.getString(1);
        } catch (Exception e) {
            log.warn("Failed to query pg_config for {}: {}", name, e.getMessage());
        }
        return null;
    }

    private void copyPgVectorLibrary(Path srcLibDir, Path targetLibDir) throws IOException {
        if (!Files.exists(srcLibDir)) return;

        try (Stream<Path> files = Files.list(srcLibDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("vector"))
                 .forEach(src -> {
                     try {
                         Path target = targetLibDir.resolve(src.getFileName());
                         Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                         try {
                             Files.setPosixFilePermissions(target,
                                     EnumSet.of(PosixFilePermission.OWNER_READ,
                                                PosixFilePermission.OWNER_WRITE,
                                                PosixFilePermission.OWNER_EXECUTE,
                                                PosixFilePermission.GROUP_READ,
                                                PosixFilePermission.GROUP_EXECUTE,
                                                PosixFilePermission.OTHERS_READ,
                                                PosixFilePermission.OTHERS_EXECUTE));
                         } catch (Exception ignored) {}
                         log.info("  -> lib: {}", target.getFileName());
                     } catch (IOException e) {
                         log.warn("Failed to copy pgvector library {}: {}", src.getFileName(), e.getMessage());
                     }
                 });
        }
    }

    private void copyPgVectorExtensionFiles(Path srcExtDir, Path targetExtDir) throws IOException {
        if (!Files.exists(srcExtDir)) return;

        try (Stream<Path> files = Files.list(srcExtDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("vector"))
                 .forEach(src -> {
                     try {
                         Path target = targetExtDir.resolve(src.getFileName());
                         Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                         log.info("  -> ext: {}", target.getFileName());
                     } catch (IOException e) {
                         log.warn("Failed to copy pgvector extension file {}: {}", src.getFileName(), e.getMessage());
                     }
                 });
        }
    }

    private Path getDataDirectory() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".inquery", "data", "postgres");
    }

    private Path getPgInstallDirectory() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".inquery", "data", "pg-install");
    }

    @PreDestroy
    public void stop() {
        if (embeddedPostgres != null) {
            try {
                log.info("Stopping embedded PostgreSQL...");
                embeddedPostgres.close();
            } catch (IOException e) {
                log.error("Error stopping embedded PostgreSQL", e);
            }
        }
    }
}
