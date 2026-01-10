/*
 * Copyright 2025 devteam@scivics-lab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.actoriac.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI subcommand to merge scattered log databases into a single database.
 *
 * <p>Before the log-server feature, each workflow run would create its own
 * separate database file. This command consolidates them into one database.</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>
 * # Scan a directory for .mv.db files and merge them
 * actor-iac merge-logs --scan ./workflows --target ./logs/merged
 *
 * # Merge specific database files
 * actor-iac merge-logs --target ./logs/merged ./db1 ./db2 ./db3
 *
 * # Dry-run to see what would be merged
 * actor-iac merge-logs --scan ./workflows --target ./logs/merged --dry-run
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.10.0
 */
@Command(
    name = "merge-logs",
    mixinStandardHelpOptions = true,
    version = "actor-IaC merge-logs 2.10.0",
    description = "Merge scattered log databases into a single database."
)
public class MergeLogsCLI implements Callable<Integer> {

    @Option(
        names = {"--target"},
        required = true,
        description = "Target database file path (without .mv.db extension)"
    )
    private File targetDb;

    @Option(
        names = {"--scan"},
        description = "Directory to scan for .mv.db files (recursive)"
    )
    private File scanDir;

    @Parameters(
        paramLabel = "SOURCE",
        description = "Source database files to merge (without .mv.db extension)",
        arity = "0..*"
    )
    private List<File> sourceDbs = new ArrayList<>();

    @Option(
        names = {"--dry-run"},
        description = "Show what would be merged without actually merging"
    )
    private boolean dryRun;

    @Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose output"
    )
    private boolean verbose;

    @Option(
        names = {"--skip-duplicates"},
        description = "Skip sessions that already exist in target (based on workflow_name and started_at)",
        defaultValue = "true"
    )
    private boolean skipDuplicates;

    /** Statistics for reporting */
    private int totalSessions = 0;
    private int totalLogs = 0;
    private int totalNodeResults = 0;
    private int skippedSessions = 0;

    @Override
    public Integer call() {
        // Collect source databases
        List<File> allSources = collectSourceDatabases();
        if (allSources.isEmpty()) {
            System.err.println("No source databases found.");
            System.err.println("Use --scan <dir> to scan for databases, or specify source files directly.");
            return 1;
        }

        // Filter out target from sources if present
        String targetPath = targetDb.getAbsolutePath();
        allSources.removeIf(f -> f.getAbsolutePath().equals(targetPath));

        if (allSources.isEmpty()) {
            System.err.println("No source databases to merge (target was the only database found).");
            return 1;
        }

        System.out.println("=".repeat(60));
        System.out.println("Log Database Merge");
        System.out.println("=".repeat(60));
        System.out.println("Target: " + targetDb.getAbsolutePath() + ".mv.db");
        System.out.println("Sources: " + allSources.size() + " database(s)");
        if (verbose) {
            for (File source : allSources) {
                System.out.println("  - " + source.getAbsolutePath() + ".mv.db");
            }
        }
        System.out.println("-".repeat(60));

        if (dryRun) {
            System.out.println("[DRY-RUN MODE - No changes will be made]");
            System.out.println();
            return dryRunAnalysis(allSources);
        }

        try {
            return performMerge(allSources);
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    /**
     * Collects all source database files from scan directory and explicit parameters.
     */
    private List<File> collectSourceDatabases() {
        List<File> sources = new ArrayList<>();

        // Add explicitly specified sources
        for (File source : sourceDbs) {
            File dbFile = new File(source.getAbsolutePath() + ".mv.db");
            if (dbFile.exists()) {
                sources.add(source);
            } else {
                System.err.println("Warning: Database not found: " + dbFile.getAbsolutePath());
            }
        }

        // Scan directory for .mv.db files
        if (scanDir != null) {
            if (!scanDir.isDirectory()) {
                System.err.println("Warning: Not a directory: " + scanDir);
            } else {
                try (Stream<Path> paths = Files.walk(scanDir.toPath())) {
                    paths.filter(Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".mv.db"))
                         .map(p -> {
                             // Remove .mv.db extension
                             String path = p.toString();
                             return new File(path.substring(0, path.length() - 6));
                         })
                         .filter(f -> !sources.contains(f))
                         .forEach(sources::add);
                } catch (IOException e) {
                    System.err.println("Warning: Failed to scan directory: " + e.getMessage());
                }
            }
        }

        return sources;
    }

    /**
     * Performs dry-run analysis showing what would be merged.
     */
    private int dryRunAnalysis(List<File> sources) {
        int totalSes = 0;
        int totalLog = 0;
        int totalNr = 0;

        for (File source : sources) {
            try (Connection conn = openDatabase(source)) {
                // Skip databases without sessions table
                if (!tableExists(conn, "sessions")) {
                    System.out.printf("%-50s (empty - no sessions table)%n",
                        truncate(source.getName(), 50));
                    continue;
                }

                int sessions = countRows(conn, "sessions");
                int logs = tableExists(conn, "logs") ? countRows(conn, "logs") : 0;
                int nodeResults = tableExists(conn, "node_results") ? countRows(conn, "node_results") : 0;

                totalSes += sessions;
                totalLog += logs;
                totalNr += nodeResults;

                System.out.printf("%-50s sessions: %4d  logs: %6d  node_results: %4d%n",
                    truncate(source.getName(), 50), sessions, logs, nodeResults);

            } catch (SQLException e) {
                System.err.println("Error reading " + source + ": " + e.getMessage());
            }
        }

        System.out.println("-".repeat(60));
        System.out.printf("%-50s sessions: %4d  logs: %6d  node_results: %4d%n",
            "TOTAL", totalSes, totalLog, totalNr);
        System.out.println("=".repeat(60));

        return 0;
    }

    /**
     * Performs the actual merge operation.
     */
    private int performMerge(List<File> sources) throws SQLException {
        // Open/create target database
        try (Connection targetConn = openDatabase(targetDb)) {
            initializeSchema(targetConn);

            // Load existing sessions for duplicate detection
            Set<String> existingSessions = skipDuplicates ? loadExistingSessions(targetConn) : Set.of();

            for (File source : sources) {
                System.out.println("Merging: " + source.getName() + ".mv.db");
                try (Connection sourceConn = openDatabase(source)) {
                    mergeDatabase(sourceConn, targetConn, existingSessions, source.getName());
                } catch (SQLException e) {
                    System.err.println("  Error: " + e.getMessage());
                    if (verbose) {
                        e.printStackTrace();
                    }
                }
            }

            targetConn.commit();
        }

        // Print summary
        System.out.println("-".repeat(60));
        System.out.println("Merge completed:");
        System.out.println("  Sessions merged:     " + totalSessions);
        System.out.println("  Sessions skipped:    " + skippedSessions + " (duplicates)");
        System.out.println("  Log entries merged:  " + totalLogs);
        System.out.println("  Node results merged: " + totalNodeResults);
        System.out.println("=".repeat(60));

        return 0;
    }

    /**
     * Opens a database connection.
     */
    private Connection openDatabase(File dbPath) throws SQLException {
        String url = "jdbc:h2:" + dbPath.getAbsolutePath();
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * Initializes the target database schema.
     */
    private void initializeSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id IDENTITY PRIMARY KEY,
                    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    ended_at TIMESTAMP,
                    workflow_name VARCHAR(255),
                    overlay_name VARCHAR(255),
                    inventory_name VARCHAR(255),
                    node_count INT,
                    status VARCHAR(20) DEFAULT 'RUNNING',
                    source_db VARCHAR(255)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    node_id VARCHAR(255) NOT NULL,
                    vertex_name CLOB,
                    action_name CLOB,
                    level VARCHAR(10) NOT NULL,
                    message CLOB,
                    exit_code INT,
                    duration_ms BIGINT,
                    FOREIGN KEY (session_id) REFERENCES sessions(id)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS node_results (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    node_id VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    reason VARCHAR(1000),
                    FOREIGN KEY (session_id) REFERENCES sessions(id)
                )
                """);

            // Add source_db column if it doesn't exist (migration)
            try {
                stmt.execute("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source_db VARCHAR(255)");
            } catch (SQLException e) {
                // Column might already exist
            }

            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_session ON logs(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_workflow ON sessions(workflow_name)");
        }
        conn.commit();
    }

    /**
     * Loads existing session keys for duplicate detection.
     */
    private Set<String> loadExistingSessions(Connection conn) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT workflow_name, started_at FROM sessions")) {
            while (rs.next()) {
                String key = makeSessionKey(rs.getString("workflow_name"), rs.getTimestamp("started_at"));
                existing.add(key);
            }
        }
        return existing;
    }

    /**
     * Creates a unique key for duplicate detection.
     */
    private String makeSessionKey(String workflowName, Timestamp startedAt) {
        return (workflowName != null ? workflowName : "") + "|" +
               (startedAt != null ? startedAt.toString() : "");
    }

    /**
     * Checks if a table exists in the database.
     */
    private boolean tableExists(Connection conn, String tableName) {
        // Use a simple query to check if table exists - more reliable than metadata
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT 1 FROM " + tableName + " WHERE 1=0");
            return true;
        } catch (SQLException e) {
            // Table doesn't exist
            return false;
        }
    }

    /**
     * Merges one source database into the target.
     */
    private void mergeDatabase(Connection source, Connection target,
                               Set<String> existingSessions, String sourceName) throws SQLException {
        // Check if sessions table exists
        if (!tableExists(source, "sessions")) {
            if (verbose) {
                System.out.println("  Skipping (no sessions table)");
            }
            return;
        }

        // Read all sessions from source
        try (Statement stmt = source.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sessions ORDER BY id")) {

            while (rs.next()) {
                long oldSessionId = rs.getLong("id");
                String workflowName = rs.getString("workflow_name");
                Timestamp startedAt = rs.getTimestamp("started_at");

                // Check for duplicates
                String sessionKey = makeSessionKey(workflowName, startedAt);
                if (existingSessions.contains(sessionKey)) {
                    if (verbose) {
                        System.out.println("  Skipping duplicate session: " + workflowName + " at " + startedAt);
                    }
                    skippedSessions++;
                    continue;
                }

                // Insert session into target and get new ID
                long newSessionId = insertSession(target, rs, sourceName);
                existingSessions.add(sessionKey);
                totalSessions++;

                // Copy logs for this session
                int logCount = copyLogs(source, target, oldSessionId, newSessionId);
                totalLogs += logCount;

                // Copy node_results for this session
                int nrCount = copyNodeResults(source, target, oldSessionId, newSessionId);
                totalNodeResults += nrCount;

                if (verbose) {
                    System.out.printf("  Session %d -> %d: %s (%d logs, %d node_results)%n",
                        oldSessionId, newSessionId, workflowName, logCount, nrCount);
                }
            }
        }
    }

    /**
     * Inserts a session into the target database.
     */
    private long insertSession(Connection target, ResultSet rs, String sourceName) throws SQLException {
        String sql = """
            INSERT INTO sessions (started_at, ended_at, workflow_name, overlay_name,
                                  inventory_name, node_count, status, source_db)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = target.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, rs.getTimestamp("started_at"));
            ps.setTimestamp(2, rs.getTimestamp("ended_at"));
            ps.setString(3, rs.getString("workflow_name"));
            ps.setString(4, rs.getString("overlay_name"));
            ps.setString(5, rs.getString("inventory_name"));
            ps.setInt(6, rs.getInt("node_count"));
            ps.setString(7, rs.getString("status"));
            ps.setString(8, sourceName);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to get generated session ID");
    }

    /**
     * Copies logs from source to target with new session ID.
     */
    private int copyLogs(Connection source, Connection target,
                         long oldSessionId, long newSessionId) throws SQLException {
        int count = 0;
        String selectSql = "SELECT * FROM logs WHERE session_id = ?";
        String insertSql = """
            INSERT INTO logs (session_id, timestamp, node_id, vertex_name, action_name,
                             level, message, exit_code, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement selectPs = source.prepareStatement(selectSql);
             PreparedStatement insertPs = target.prepareStatement(insertSql)) {
            selectPs.setLong(1, oldSessionId);

            try (ResultSet rs = selectPs.executeQuery()) {
                while (rs.next()) {
                    insertPs.setLong(1, newSessionId);
                    insertPs.setTimestamp(2, rs.getTimestamp("timestamp"));
                    insertPs.setString(3, rs.getString("node_id"));
                    insertPs.setString(4, rs.getString("vertex_name"));
                    insertPs.setString(5, rs.getString("action_name"));
                    insertPs.setString(6, rs.getString("level"));
                    insertPs.setString(7, rs.getString("message"));

                    int exitCode = rs.getInt("exit_code");
                    if (rs.wasNull()) {
                        insertPs.setNull(8, Types.INTEGER);
                    } else {
                        insertPs.setInt(8, exitCode);
                    }

                    long durationMs = rs.getLong("duration_ms");
                    if (rs.wasNull()) {
                        insertPs.setNull(9, Types.BIGINT);
                    } else {
                        insertPs.setLong(9, durationMs);
                    }

                    insertPs.executeUpdate();
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Copies node_results from source to target with new session ID.
     */
    private int copyNodeResults(Connection source, Connection target,
                                long oldSessionId, long newSessionId) throws SQLException {
        int count = 0;
        String selectSql = "SELECT * FROM node_results WHERE session_id = ?";
        String insertSql = """
            INSERT INTO node_results (session_id, node_id, status, reason)
            VALUES (?, ?, ?, ?)
            """;

        try (PreparedStatement selectPs = source.prepareStatement(selectSql);
             PreparedStatement insertPs = target.prepareStatement(insertSql)) {
            selectPs.setLong(1, oldSessionId);

            try (ResultSet rs = selectPs.executeQuery()) {
                while (rs.next()) {
                    insertPs.setLong(1, newSessionId);
                    insertPs.setString(2, rs.getString("node_id"));
                    insertPs.setString(3, rs.getString("status"));
                    insertPs.setString(4, rs.getString("reason"));
                    insertPs.executeUpdate();
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Counts rows in a table.
     */
    private int countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Truncates a string to max length with ellipsis.
     */
    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }
}
