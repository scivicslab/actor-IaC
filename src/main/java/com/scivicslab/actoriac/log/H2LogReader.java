/*
 * Copyright 2025 devteam@scivicslab.com
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
 * <p>This class provides access to the H2 log database for querying logs.
 * Uses AUTO_SERVER=TRUE to connect to the H2 server started by the writer process,
 * allowing concurrent access while actor-IaC is writing logs.</p>
 *
 * @author devteam@scivicslab.com
 */
public class H2LogReader implements AutoCloseable {

    private final Connection connection;
    private final boolean ownsConnection;

    /**
     * Opens the log database for reading.
     *
     * @param dbPath path to the database file (without extension)
     * @throws SQLException if database connection fails
     */
    public H2LogReader(Path dbPath) throws SQLException {
        // Connect to the database using AUTO_SERVER=TRUE.
        // This allows connecting to the server started by H2LogStore or log-server,
        // or starting a new server if none exists.
        String url = "jdbc:h2:" + dbPath.toAbsolutePath().toString() + ";AUTO_SERVER=TRUE";
        this.connection = DriverManager.getConnection(url);
        this.ownsConnection = true;
    }

    /**
     * Opens a remote log database via H2 TCP server.
     *
     * <p>Connects to an H2 log server started with the {@code log-server} command.</p>
     *
     * @param host H2 server hostname (typically "localhost")
     * @param port H2 server TCP port
     * @param dbPath database path on the server
     * @throws SQLException if database connection fails
     */
    public H2LogReader(String host, int port, String dbPath) throws SQLException {
        String url = "jdbc:h2:tcp://" + host + ":" + port + "/" + dbPath;
        this.connection = DriverManager.getConnection(url);
        this.ownsConnection = true;
    }

    /**
     * Creates a reader using an existing connection.
     *
     * <p>Used internally by H2LogStore to delegate read operations.
     * The connection will NOT be closed when this reader is closed.</p>
     *
     * @param connection the database connection to use
     */
    public H2LogReader(Connection connection) {
        this.connection = connection;
        this.ownsConnection = false;
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
            String overlayName = null;
            String inventoryName = null;
            LocalDateTime startedAt = null;
            LocalDateTime endedAt = null;
            int nodeCount = 0;
            SessionStatus status = SessionStatus.RUNNING;

            // Execution context
            String cwd = null;
            String gitCommit = null;
            String gitBranch = null;
            String commandLine = null;
            String actorIacVersion = null;
            String actorIacCommit = null;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM sessions WHERE id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        workflowName = rs.getString("workflow_name");
                        overlayName = rs.getString("overlay_name");
                        inventoryName = rs.getString("inventory_name");
                        Timestamp ts = rs.getTimestamp("started_at");
                        startedAt = ts != null ? ts.toLocalDateTime() : null;
                        ts = rs.getTimestamp("ended_at");
                        endedAt = ts != null ? ts.toLocalDateTime() : null;
                        nodeCount = rs.getInt("node_count");
                        String statusStr = rs.getString("status");
                        if (statusStr != null) {
                            status = SessionStatus.valueOf(statusStr);
                        }

                        // Read execution context (may be null for older sessions)
                        cwd = getStringOrNull(rs, "cwd");
                        gitCommit = getStringOrNull(rs, "git_commit");
                        gitBranch = getStringOrNull(rs, "git_branch");
                        commandLine = getStringOrNull(rs, "command_line");
                        actorIacVersion = getStringOrNull(rs, "actoriac_version");
                        actorIacCommit = getStringOrNull(rs, "actoriac_commit");
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

            // Use actual node count from node_results if available
            int actualNodeCount = successCount + failedCount;
            if (actualNodeCount > 0) {
                nodeCount = actualNodeCount;
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

            return new SessionSummary(sessionId, workflowName, overlayName, inventoryName,
                    startedAt, endedAt, nodeCount, status, successCount, failedCount,
                    failedNodes, totalLogEntries, errorCount,
                    cwd, gitCommit, gitBranch, commandLine, actorIacVersion, actorIacCommit);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to get session summary", e);
        }
    }

    /**
     * Safely gets a string column value, returning null if the column doesn't exist.
     */
    private String getStringOrNull(ResultSet rs, String columnName) {
        try {
            return rs.getString(columnName);
        } catch (SQLException e) {
            // Column may not exist in older databases
            return null;
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

    /**
     * Lists sessions filtered by criteria.
     *
     * @param workflowName filter by workflow name (null to skip)
     * @param overlayName filter by overlay name (null to skip)
     * @param inventoryName filter by inventory name (null to skip)
     * @param startedAfter filter by start time (null to skip)
     * @param limit maximum number of sessions to return
     * @return list of session summaries matching the criteria
     */
    public List<SessionSummary> listSessionsFiltered(String workflowName, String overlayName,
                                                      String inventoryName, LocalDateTime startedAfter,
                                                      int limit) {
        return listSessionsFiltered(workflowName, overlayName, inventoryName, startedAfter, null, limit);
    }

    /**
     * Lists sessions filtered by criteria including end time.
     *
     * @param workflowName filter by workflow name (null to skip)
     * @param overlayName filter by overlay name (null to skip)
     * @param inventoryName filter by inventory name (null to skip)
     * @param startedAfter filter by start time (null to skip)
     * @param endedAfter filter by end time (null to skip)
     * @param limit maximum number of sessions to return
     * @return list of session summaries matching the criteria
     */
    public List<SessionSummary> listSessionsFiltered(String workflowName, String overlayName,
                                                      String inventoryName, LocalDateTime startedAfter,
                                                      LocalDateTime endedAfter, int limit) {
        List<SessionSummary> sessions = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id FROM sessions WHERE 1=1");

        if (workflowName != null) {
            sql.append(" AND workflow_name = ?");
        }
        if (overlayName != null) {
            sql.append(" AND overlay_name = ?");
        }
        if (inventoryName != null) {
            sql.append(" AND inventory_name = ?");
        }
        if (startedAfter != null) {
            sql.append(" AND started_at >= ?");
        }
        if (endedAfter != null) {
            sql.append(" AND ended_at >= ?");
        }
        sql.append(" ORDER BY started_at DESC LIMIT ?");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            if (workflowName != null) {
                ps.setString(idx++, workflowName);
            }
            if (overlayName != null) {
                ps.setString(idx++, overlayName);
            }
            if (inventoryName != null) {
                ps.setString(idx++, inventoryName);
            }
            if (startedAfter != null) {
                ps.setTimestamp(idx++, Timestamp.valueOf(startedAfter));
            }
            if (endedAfter != null) {
                ps.setTimestamp(idx++, Timestamp.valueOf(endedAfter));
            }
            ps.setInt(idx, limit);

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

    /**
     * Lists sessions filtered by workflow name.
     *
     * @param workflowName the workflow name to filter by
     * @param limit maximum number of sessions to return
     * @return list of session summaries for the specified workflow
     */
    public List<SessionSummary> listSessionsByWorkflow(String workflowName, int limit) {
        return listSessionsFiltered(workflowName, null, null, null, limit);
    }

    /**
     * Lists sessions filtered by overlay name.
     *
     * @param overlayName the overlay name to filter by
     * @param limit maximum number of sessions to return
     * @return list of session summaries for the specified overlay
     */
    public List<SessionSummary> listSessionsByOverlay(String overlayName, int limit) {
        return listSessionsFiltered(null, overlayName, null, null, limit);
    }

    /**
     * Lists sessions filtered by inventory name.
     *
     * @param inventoryName the inventory name to filter by
     * @param limit maximum number of sessions to return
     * @return list of session summaries for the specified inventory
     */
    public List<SessionSummary> listSessionsByInventory(String inventoryName, int limit) {
        return listSessionsFiltered(null, null, inventoryName, null, limit);
    }

    /**
     * Lists sessions started after the specified time.
     *
     * @param startedAfter only include sessions started after this time
     * @param limit maximum number of sessions to return
     * @return list of session summaries started after the specified time
     */
    public List<SessionSummary> listSessionsAfter(LocalDateTime startedAfter, int limit) {
        return listSessionsFiltered(null, null, null, startedAfter, limit);
    }

    /**
     * Information about a node in a session.
     *
     * @param nodeId the node identifier
     * @param status the node status (SUCCESS, FAILED, or null if not yet recorded)
     * @param logCount the number of log entries for this node
     */
    public record NodeInfo(String nodeId, String status, int logCount) {}

    /**
     * Gets all nodes that participated in a session.
     *
     * <p>Returns only nodes that have results recorded in node_results table,
     * which represents actual workflow target nodes (not internal actors like
     * cli, nodeGroup, etc.).</p>
     *
     * @param sessionId the session ID
     * @return list of node information, ordered by node ID
     */
    public List<NodeInfo> getNodesInSession(long sessionId) {
        List<NodeInfo> nodes = new ArrayList<>();
        String sql = """
            SELECT nr.node_id,
                   nr.status,
                   COUNT(l.id) as log_count
            FROM node_results nr
            LEFT JOIN logs l ON nr.session_id = l.session_id AND nr.node_id = l.node_id
            WHERE nr.session_id = ?
            GROUP BY nr.node_id, nr.status
            ORDER BY nr.node_id
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.add(new NodeInfo(
                            rs.getString("node_id"),
                            rs.getString("status"),
                            rs.getInt("log_count")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get nodes in session", e);
        }
        return nodes;
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
                rs.getString("label"),
                rs.getString("action_name"),
                level,
                rs.getString("message"),
                exitCode,
                durationMs
        );
    }

    @Override
    public void close() throws SQLException {
        if (ownsConnection) {
            connection.close();
        }
    }
}
