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

package com.scivicslab.actoriac.log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * H2 Database implementation of DistributedLogStore.
 *
 * <p>Uses H2 embedded database for storing logs from distributed workflow
 * execution. Supports concurrent access from multiple threads and provides
 * efficient querying by node, level, and time.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Pure Java - no native dependencies</li>
 *   <li>Single file storage (.mv.db)</li>
 *   <li>Asynchronous batch writing for performance</li>
 *   <li>SQL-based querying</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 */
public class H2LogStore implements DistributedLogStore {

    private static final Logger LOG = Logger.getLogger(H2LogStore.class.getName());
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final Connection connection;
    private final H2LogReader reader;
    private final BlockingQueue<LogTask> writeQueue;
    private final Thread writerThread;
    private final AtomicBoolean running;
    private static final int BATCH_SIZE = 100;
    private static final int DEFAULT_TCP_PORT = 29090;

    /**
     * Optional text log file writer.
     * When set, log entries are also written to this text file.
     */
    private PrintWriter textLogWriter;

    /**
     * Creates an H2LogStore with the specified database path.
     *
     * @param dbPath path to the database file (without extension)
     * @throws SQLException if database connection fails
     */
    public H2LogStore(Path dbPath) throws SQLException {
        // AUTO_SERVER=TRUE allows multiple processes to access the same DB
        // First process starts embedded server, others connect via TCP
        String url = "jdbc:h2:" + dbPath.toAbsolutePath().toString() + ";AUTO_SERVER=TRUE";
        this.connection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(connection);
        this.writeQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        initSchema();

        // Start async writer thread
        this.writerThread = new Thread(this::writerLoop, "H2LogStore-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Creates an in-memory H2LogStore (for testing).
     *
     * @throws SQLException if database connection fails
     */
    public H2LogStore() throws SQLException {
        // Use unique DB name per instance to avoid test interference
        String url = "jdbc:h2:mem:testdb_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        this.connection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(connection);
        this.writeQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        initSchema();

        this.writerThread = new Thread(this::writerLoop, "H2LogStore-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Creates an H2LogStore connected to a remote TCP server.
     *
     * <p>The server should be started using the {@code log-server} command
     * before connecting. Schema is expected to be initialized by the server.</p>
     *
     * @param host H2 server hostname (typically "localhost")
     * @param port H2 server TCP port
     * @param dbPath database path on the server
     * @throws SQLException if connection fails
     */
    public H2LogStore(String host, int port, String dbPath) throws SQLException {
        String url = "jdbc:h2:tcp://" + host + ":" + port + "/" + dbPath;
        this.connection = DriverManager.getConnection(url);
        this.reader = new H2LogReader(connection);
        this.writeQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        // Schema should already be initialized by the server
        verifySchema();

        this.writerThread = new Thread(this::writerLoop, "H2LogStore-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Factory method that attempts TCP connection with fallback to embedded mode.
     *
     * <p>If a log server address is specified and reachable, connects via TCP.
     * Otherwise, falls back to embedded mode with AUTO_SERVER=TRUE.</p>
     *
     * @param logServer server address in "host:port" format (may be null)
     * @param embeddedPath path for embedded database fallback
     * @return H2LogStore instance
     * @throws SQLException if both TCP and embedded connections fail
     */
    public static H2LogStore createWithFallback(String logServer, Path embeddedPath)
            throws SQLException {
        if (logServer != null && !logServer.isBlank()) {
            try {
                String[] parts = logServer.split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : DEFAULT_TCP_PORT;

                // Use the embedded path as the database name on the server
                String dbPath = embeddedPath.toAbsolutePath().toString();

                H2LogStore store = new H2LogStore(host, port, dbPath);
                LOG.info("Connected to log server at " + logServer);
                return store;
            } catch (SQLException e) {
                LOG.warning("Failed to connect to log server '" + logServer +
                           "', falling back to embedded mode: " + e.getMessage());
            } catch (NumberFormatException e) {
                LOG.warning("Invalid log server port in '" + logServer +
                           "', falling back to embedded mode");
            }
        }
        return new H2LogStore(embeddedPath);
    }

    /**
     * Sets the text log file for additional text-based logging.
     *
     * <p>When a text log file is set, all log entries written to the database
     * are also appended to this text file in a human-readable format.</p>
     *
     * @param textLogPath path to the text log file
     * @throws IOException if the file cannot be opened for writing
     */
    public void setTextLogFile(Path textLogPath) throws IOException {
        if (this.textLogWriter != null) {
            this.textLogWriter.close();
        }
        this.textLogWriter = new PrintWriter(new BufferedWriter(new FileWriter(textLogPath.toFile(), true)), true);
        LOG.info("Text logging enabled: " + textLogPath);
    }

    /**
     * Disables text file logging.
     */
    public void disableTextLog() {
        if (this.textLogWriter != null) {
            this.textLogWriter.close();
            this.textLogWriter = null;
        }
    }

    /**
     * Verifies the database schema exists (for TCP connections).
     *
     * @throws SQLException if schema is not initialized
     */
    private void verifySchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Simple check - if this fails, schema doesn't exist
            stmt.execute("SELECT 1 FROM sessions WHERE 1=0");
            stmt.execute("SELECT 1 FROM logs WHERE 1=0");
            stmt.execute("SELECT 1 FROM node_results WHERE 1=0");
        } catch (SQLException e) {
            throw new SQLException("Database schema not initialized. " +
                    "Ensure log-server has been started at least once.", e);
        }
    }

    private void initSchema() throws SQLException {
        initSchema(connection);
    }

    /**
     * Initializes the log database schema on the given connection.
     *
     * <p>This method can be called from external components (e.g., log server)
     * to ensure consistent schema across all database access points.</p>
     *
     * @param conn the database connection
     * @throws SQLException if schema initialization fails
     */
    public static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Sessions table
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
                    cwd VARCHAR(1000),
                    git_commit VARCHAR(50),
                    git_branch VARCHAR(255),
                    command_line VARCHAR(2000),
                    actoriac_version VARCHAR(50),
                    actoriac_commit VARCHAR(50)
                )
                """);

            // Logs table
            // label and action_name use CLOB to store long YAML snippets
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    node_id VARCHAR(255) NOT NULL,
                    label CLOB,
                    action_name CLOB,
                    level VARCHAR(10) NOT NULL,
                    message CLOB,
                    exit_code INT,
                    duration_ms BIGINT,
                    FOREIGN KEY (session_id) REFERENCES sessions(id)
                )
                """);

            // Node results table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS node_results (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    node_id VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    reason VARCHAR(1000),
                    FOREIGN KEY (session_id) REFERENCES sessions(id),
                    UNIQUE (session_id, node_id)
                )
                """);

            // Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_session ON logs(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_node ON logs(node_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_workflow ON sessions(workflow_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_overlay ON sessions(overlay_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_inventory ON sessions(inventory_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at)");
        }
    }

    private void writerLoop() {
        List<LogTask> batch = new ArrayList<>(BATCH_SIZE);
        while (running.get() || !writeQueue.isEmpty()) {
            try {
                LogTask task = writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (task != null) {
                    batch.add(task);
                    writeQueue.drainTo(batch, BATCH_SIZE - 1);
                    processBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Process remaining
        if (!batch.isEmpty()) {
            processBatch(batch);
        }
    }

    private void processBatch(List<LogTask> batch) {
        try {
            connection.setAutoCommit(false);
            for (LogTask task : batch) {
                task.execute(connection);
            }
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                // Ignore rollback errors
            }
            e.printStackTrace();
        }
    }

    @Override
    public long startSession(String workflowName, int nodeCount) {
        return startSession(workflowName, null, null, nodeCount);
    }

    @Override
    public long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount) {
        return startSession(workflowName, overlayName, inventoryName, nodeCount,
                            null, null, null, null, null, null);
    }

    @Override
    public long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount,
                             String cwd, String gitCommit, String gitBranch,
                             String commandLine, String actorIacVersion, String actorIacCommit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions (workflow_name, overlay_name, inventory_name, node_count, " +
                "cwd, git_commit, git_branch, command_line, actoriac_version, actoriac_commit) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workflowName);
            ps.setString(2, overlayName);
            ps.setString(3, inventoryName);
            ps.setInt(4, nodeCount);
            ps.setString(5, cwd);
            ps.setString(6, gitCommit);
            ps.setString(7, gitBranch);
            ps.setString(8, commandLine);
            ps.setString(9, actorIacVersion);
            ps.setString(10, actorIacCommit);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to start session", e);
        }
        return -1;
    }

    @Override
    public void log(long sessionId, String nodeId, LogLevel level, String message) {
        log(sessionId, nodeId, null, level, message);
    }

    @Override
    public void log(long sessionId, String nodeId, String label, LogLevel level, String message) {
        writeQueue.offer(new LogTask.InsertLog(sessionId, nodeId, label, null, level, message, null, null));
        writeToTextLog(nodeId, label, level, message);
    }

    @Override
    public void logAction(long sessionId, String nodeId, String label,
                          String actionName, int exitCode, long durationMs, String output) {
        LogLevel level = parseLogLevelFromLabel(label, exitCode);
        writeQueue.offer(new LogTask.InsertLog(sessionId, nodeId, label, actionName, level, output, exitCode, durationMs));
        writeToTextLog(nodeId, label, level, output);
    }

    /**
     * Parses log level from the label parameter.
     *
     * <p>Maps java.util.logging levels to LogLevel:</p>
     * <ul>
     *   <li>SEVERE → ERROR</li>
     *   <li>WARNING → WARN</li>
     *   <li>INFO → INFO</li>
     *   <li>CONFIG, FINE, FINER, FINEST → DEBUG</li>
     * </ul>
     */
    private LogLevel parseLogLevelFromLabel(String label, int exitCode) {
        if (label != null && label.startsWith("log-")) {
            String levelName = label.substring(4).toUpperCase();
            return switch (levelName) {
                case "SEVERE" -> LogLevel.ERROR;
                case "WARNING" -> LogLevel.WARN;
                case "INFO" -> LogLevel.INFO;
                case "CONFIG", "FINE", "FINER", "FINEST" -> LogLevel.DEBUG;
                default -> exitCode == 0 ? LogLevel.INFO : LogLevel.ERROR;
            };
        }
        return exitCode == 0 ? LogLevel.INFO : LogLevel.ERROR;
    }

    /**
     * Writes a log entry to the text log file if enabled.
     *
     * @param nodeId the node identifier
     * @param label the workflow label (may be null)
     * @param level the log level
     * @param message the log message
     */
    private void writeToTextLog(String nodeId, String label, LogLevel level, String message) {
        if (textLogWriter == null) {
            return;
        }
        String timestamp = LocalDateTime.now().atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
        String labelPart = label != null ? " [" + label + "]" : "";
        textLogWriter.printf("%s %s %s%s %s%n", timestamp, level, nodeId, labelPart, message);
    }

    @Override
    public void markNodeSuccess(long sessionId, String nodeId) {
        writeQueue.offer(new LogTask.UpdateNodeResult(sessionId, nodeId, "SUCCESS", null));
    }

    @Override
    public void markNodeFailed(long sessionId, String nodeId, String reason) {
        writeQueue.offer(new LogTask.UpdateNodeResult(sessionId, nodeId, "FAILED", reason));
    }

    @Override
    public void endSession(long sessionId, SessionStatus status) {
        // Flush pending writes
        flushWrites();

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET ended_at = CURRENT_TIMESTAMP, status = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to end session", e);
        }
    }

    private void flushWrites() {
        // Wait for queue to drain
        while (!writeQueue.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public List<LogEntry> getLogsByNode(long sessionId, String nodeId) {
        return reader.getLogsByNode(sessionId, nodeId);
    }

    @Override
    public List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel) {
        return reader.getLogsByLevel(sessionId, minLevel);
    }

    @Override
    public SessionSummary getSummary(long sessionId) {
        return reader.getSummary(sessionId);
    }

    @Override
    public long getLatestSessionId() {
        return reader.getLatestSessionId();
    }

    @Override
    public List<SessionSummary> listSessions(int limit) {
        return reader.listSessions(limit);
    }

    @Override
    public void close() throws Exception {
        running.set(false);
        writerThread.interrupt();
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (textLogWriter != null) {
            textLogWriter.close();
            textLogWriter = null;
        }
        connection.close();
    }

    // Internal task classes for async writing
    private interface LogTask {
        void execute(Connection conn) throws SQLException;

        record InsertLog(long sessionId, String nodeId, String label, String actionName,
                         LogLevel level, String message, Integer exitCode, Long durationMs) implements LogTask {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO logs (session_id, node_id, label, action_name, level, message, exit_code, duration_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, sessionId);
                    ps.setString(2, nodeId);
                    ps.setString(3, label);
                    ps.setString(4, actionName);
                    ps.setString(5, level.name());
                    ps.setString(6, message);
                    if (exitCode != null) {
                        ps.setInt(7, exitCode);
                    } else {
                        ps.setNull(7, Types.INTEGER);
                    }
                    if (durationMs != null) {
                        ps.setLong(8, durationMs);
                    } else {
                        ps.setNull(8, Types.BIGINT);
                    }
                    ps.executeUpdate();
                }
            }
        }

        record UpdateNodeResult(long sessionId, String nodeId, String status, String reason) implements LogTask {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "MERGE INTO node_results (session_id, node_id, status, reason) KEY (session_id, node_id) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, sessionId);
                    ps.setString(2, nodeId);
                    ps.setString(3, status);
                    ps.setString(4, reason);
                    ps.executeUpdate();
                }
            }
        }
    }
}
