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

/**
 * Read-only H2 log reader for querying workflow logs.
 *
 * <p>This class provides read-only access to the H2 log database,
 * allowing concurrent access while actor-IaC is writing logs.</p>
 *
 * <p>Uses ACCESS_MODE_DATA=r for read-only file access.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class H2LogReader implements AutoCloseable {

    private final Connection connection;

    /**
     * Opens the log database in read-only mode.
     *
     * @param dbPath path to the database file (without extension)
     * @throws SQLException if database connection fails
     */
    public H2LogReader(Path dbPath) throws SQLException {
        // ACCESS_MODE_DATA=r opens in read-only mode
        // AUTO_SERVER=TRUE allows connection while another process is writing
        String url = "jdbc:h2:" + dbPath.toAbsolutePath().toString()
                   + ";ACCESS_MODE_DATA=r;AUTO_SERVER=TRUE";
        this.connection = DriverManager.getConnection(url);
    }

    /**
     * Gets logs filtered by node ID.
     *
     * @param sessionId the session ID
     * @param nodeId the node ID to filter by
     * @return list of log entries for the specified node
     */
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

    /**
     * Gets logs filtered by minimum log level.
     *
     * @param sessionId the session ID
     * @param minLevel the minimum log level to include
     * @return list of log entries at or above the specified level
     */
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
                    ps.setString(idx++, "NONE");
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

    /**
     * Gets a summary of the specified session.
     *
     * @param sessionId the session ID
     * @return the session summary, or null if not found
     */
    public SessionSummary getSummary(long sessionId) {
        try {
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
                    } else {
                        return null;
                    }
                }
            }

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

    /**
     * Gets the latest session ID.
     *
     * @return the latest session ID, or -1 if no sessions exist
     */
    public long getLatestSessionId() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(id) FROM sessions")) {
            if (rs.next()) {
                long id = rs.getLong(1);
                return rs.wasNull() ? -1 : id;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get latest session", e);
        }
        return -1;
    }

    /**
     * Lists recent sessions.
     *
     * @param limit maximum number of sessions to return
     * @return list of session summaries, ordered by start time descending
     */
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
    public void close() throws SQLException {
        connection.close();
    }
}
