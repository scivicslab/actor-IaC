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

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final Connection connection;
    private final BlockingQueue<LogTask> writeQueue;
    private final Thread writerThread;
    private final AtomicBoolean running;
    private static final int BATCH_SIZE = 100;

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
        this.writeQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);

        initSchema();

        this.writerThread = new Thread(this::writerLoop, "H2LogStore-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Sessions table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id IDENTITY PRIMARY KEY,
                    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    ended_at TIMESTAMP,
                    workflow_name VARCHAR(255),
                    node_count INT,
                    status VARCHAR(20) DEFAULT 'RUNNING'
                )
                """);

            // Logs table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    node_id VARCHAR(255) NOT NULL,
                    vertex_name VARCHAR(255),
                    action_name VARCHAR(255),
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
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions (workflow_name, node_count) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workflowName);
            ps.setInt(2, nodeCount);
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
    public void log(long sessionId, String nodeId, String vertexName, LogLevel level, String message) {
        writeQueue.offer(new LogTask.InsertLog(sessionId, nodeId, vertexName, null, level, message, null, null));
    }

    @Override
    public void logAction(long sessionId, String nodeId, String vertexName,
                          String actionName, int exitCode, long durationMs, String output) {
        LogLevel level = exitCode == 0 ? LogLevel.INFO : LogLevel.ERROR;
        writeQueue.offer(new LogTask.InsertLog(sessionId, nodeId, vertexName, actionName, level, output, exitCode, durationMs));
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
        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM logs WHERE session_id = ? AND node_id = ? ORDER BY timestamp")) {
            ps.setLong(1, sessionId);
            ps.setString(2, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query logs", e);
        }
        return entries;
    }

    @Override
    public List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel) {
        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM logs WHERE session_id = ? AND level IN (?, ?, ?, ?) ORDER BY timestamp")) {
            ps.setLong(1, sessionId);
            int idx = 2;
            for (LogLevel level : LogLevel.values()) {
                if (level.isAtLeast(minLevel)) {
                    ps.setString(idx++, level.name());
                } else {
                    ps.setString(idx++, "NONE"); // Placeholder
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapLogEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query logs", e);
        }
        return entries;
    }

    @Override
    public SessionSummary getSummary(long sessionId) {
        try {
            // Get session info
            String workflowName = null;
            LocalDateTime startedAt = null;
            LocalDateTime endedAt = null;
            int nodeCount = 0;
            SessionStatus status = SessionStatus.RUNNING;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sessions WHERE id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        workflowName = rs.getString("workflow_name");
                        Timestamp ts = rs.getTimestamp("started_at");
                        startedAt = ts != null ? ts.toLocalDateTime() : null;
                        ts = rs.getTimestamp("ended_at");
                        endedAt = ts != null ? ts.toLocalDateTime() : null;
                        nodeCount = rs.getInt("node_count");
                        String statusStr = rs.getString("status");
                        if (statusStr != null) {
                            status = SessionStatus.valueOf(statusStr);
                        }
                    }
                }
            }

            // Get node results
            int successCount = 0;
            int failedCount = 0;
            List<String> failedNodes = new ArrayList<>();

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT node_id, status FROM node_results WHERE session_id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String nodeStatus = rs.getString("status");
                        if ("SUCCESS".equals(nodeStatus)) {
                            successCount++;
                        } else {
                            failedCount++;
                            failedNodes.add(rs.getString("node_id"));
                        }
                    }
                }
            }

            // Get log counts
            int totalLogEntries = 0;
            int errorCount = 0;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) as total, SUM(CASE WHEN level = 'ERROR' THEN 1 ELSE 0 END) as errors " +
                    "FROM logs WHERE session_id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalLogEntries = rs.getInt("total");
                        errorCount = rs.getInt("errors");
                    }
                }
            }

            return new SessionSummary(sessionId, workflowName, startedAt, endedAt,
                    nodeCount, status, successCount, failedCount, failedNodes,
                    totalLogEntries, errorCount);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to get session summary", e);
        }
    }

    @Override
    public long getLatestSessionId() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(id) FROM sessions")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get latest session", e);
        }
        return -1;
    }

    @Override
    public List<SessionSummary> listSessions(int limit) {
        List<SessionSummary> sessions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM sessions ORDER BY started_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessions.add(getSummary(rs.getLong("id")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
        return sessions;
    }

    private LogEntry mapLogEntry(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("timestamp");
        Integer exitCode = rs.getObject("exit_code") != null ? rs.getInt("exit_code") : null;
        Long durationMs = rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null;
        String levelStr = rs.getString("level");
        LogLevel level = levelStr != null ? LogLevel.valueOf(levelStr) : LogLevel.INFO;

        return new LogEntry(
                rs.getLong("id"),
                rs.getLong("session_id"),
                ts != null ? ts.toLocalDateTime() : null,
                rs.getString("node_id"),
                rs.getString("vertex_name"),
                rs.getString("action_name"),
                level,
                rs.getString("message"),
                exitCode,
                durationMs
        );
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
        connection.close();
    }

    // Internal task classes for async writing
    private interface LogTask {
        void execute(Connection conn) throws SQLException;

        record InsertLog(long sessionId, String nodeId, String vertexName, String actionName,
                         LogLevel level, String message, Integer exitCode, Long durationMs) implements LogTask {
            @Override
            public void execute(Connection conn) throws SQLException {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO logs (session_id, node_id, vertex_name, action_name, level, message, exit_code, duration_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, sessionId);
                    ps.setString(2, nodeId);
                    ps.setString(3, vertexName);
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
