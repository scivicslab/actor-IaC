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

import java.util.List;

/**
 * Interface for distributed log storage.
 *
 * <p>This interface defines operations for storing and querying logs
 * from distributed workflow execution across multiple nodes.</p>
 *
 * @author devteam@scivicslab.com
 */
public interface DistributedLogStore extends AutoCloseable {

    /**
     * Starts a new workflow execution session.
     *
     * @param workflowName name of the workflow being executed
     * @param nodeCount number of nodes participating in this session
     * @return session ID for subsequent log entries
     */
    long startSession(String workflowName, int nodeCount);

    /**
     * Starts a new workflow execution session with overlay and inventory info.
     *
     * @param workflowName name of the workflow being executed
     * @param overlayName name of the overlay being used (may be null)
     * @param inventoryName name of the inventory file being used (may be null)
     * @param nodeCount number of nodes participating in this session
     * @return session ID for subsequent log entries
     */
    long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount);

    /**
     * Starts a new workflow execution session with full execution context.
     *
     * @param workflowName name of the workflow being executed
     * @param overlayName name of the overlay being used (may be null)
     * @param inventoryName name of the inventory file being used (may be null)
     * @param nodeCount number of nodes participating in this session
     * @param cwd current working directory
     * @param gitCommit git commit hash of the workflow directory (may be null)
     * @param gitBranch git branch name (may be null)
     * @param commandLine the command line used to invoke actor-IaC
     * @param actorIacVersion actor-IaC version
     * @param actorIacCommit actor-IaC git commit hash (may be null)
     * @return session ID for subsequent log entries
     */
    default long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount,
                              String cwd, String gitCommit, String gitBranch,
                              String commandLine, String actorIacVersion, String actorIacCommit) {
        // Default implementation for backward compatibility
        return startSession(workflowName, overlayName, inventoryName, nodeCount);
    }

    /**
     * Records a log entry.
     *
     * @param sessionId session ID from startSession()
     * @param nodeId identifier of the node generating this log
     * @param level log level
     * @param message log message
     */
    void log(long sessionId, String nodeId, LogLevel level, String message);

    /**
     * Records a log entry with transition context.
     *
     * @param sessionId session ID from startSession()
     * @param nodeId identifier of the node
     * @param label current label in workflow
     * @param level log level
     * @param message log message
     */
    void log(long sessionId, String nodeId, String label, LogLevel level, String message);

    /**
     * Records an action result.
     *
     * @param sessionId session ID
     * @param nodeId node identifier
     * @param label label
     * @param actionName action/method name
     * @param exitCode command exit code (0 for success)
     * @param durationMs execution duration in milliseconds
     * @param output command output or result message
     */
    void logAction(long sessionId, String nodeId, String label,
                   String actionName, int exitCode, long durationMs, String output);

    /**
     * Marks a node as succeeded in this session.
     *
     * @param sessionId session ID
     * @param nodeId node identifier
     */
    void markNodeSuccess(long sessionId, String nodeId);

    /**
     * Marks a node as failed in this session.
     *
     * @param sessionId session ID
     * @param nodeId node identifier
     * @param reason failure reason
     */
    void markNodeFailed(long sessionId, String nodeId, String reason);

    /**
     * Ends a session with the given status.
     *
     * @param sessionId session ID
     * @param status final status
     */
    void endSession(long sessionId, SessionStatus status);

    /**
     * Retrieves all log entries for a specific node in a session.
     *
     * @param sessionId session ID
     * @param nodeId node identifier
     * @return list of log entries
     */
    List<LogEntry> getLogsByNode(long sessionId, String nodeId);

    /**
     * Retrieves log entries filtered by level.
     *
     * @param sessionId session ID
     * @param minLevel minimum log level to include
     * @return list of log entries
     */
    List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel);

    /**
     * Retrieves error logs for a session.
     *
     * @param sessionId session ID
     * @return list of error log entries
     */
    default List<LogEntry> getErrors(long sessionId) {
        return getLogsByLevel(sessionId, LogLevel.ERROR);
    }

    /**
     * Gets a summary of the session.
     *
     * @param sessionId session ID
     * @return session summary
     */
    SessionSummary getSummary(long sessionId);

    /**
     * Gets the most recent session ID.
     *
     * @return latest session ID, or -1 if no sessions exist
     */
    long getLatestSessionId();

    /**
     * Lists all sessions.
     *
     * @param limit maximum number of sessions to return
     * @return list of session summaries
     */
    List<SessionSummary> listSessions(int limit);
}
