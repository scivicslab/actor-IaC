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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Summary of a workflow execution session.
 *
 * @author devteam@scivics-lab.com
 */
public class SessionSummary {
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final long sessionId;
    private final String workflowName;
    private final String overlayName;
    private final String inventoryName;
    private final LocalDateTime startedAt;
    private final LocalDateTime endedAt;
    private final int nodeCount;
    private final SessionStatus status;
    private final int successCount;
    private final int failedCount;
    private final List<String> failedNodes;
    private final int totalLogEntries;
    private final int errorCount;

    // Execution context for reproducibility
    private final String cwd;
    private final String gitCommit;
    private final String gitBranch;
    private final String commandLine;
    private final String actorIacVersion;
    private final String actorIacCommit;

    /**
     * Legacy constructor for backward compatibility.
     */
    public SessionSummary(long sessionId, String workflowName, String overlayName,
                          String inventoryName, LocalDateTime startedAt,
                          LocalDateTime endedAt, int nodeCount, SessionStatus status,
                          int successCount, int failedCount, List<String> failedNodes,
                          int totalLogEntries, int errorCount) {
        this(sessionId, workflowName, overlayName, inventoryName, startedAt, endedAt,
             nodeCount, status, successCount, failedCount, failedNodes, totalLogEntries, errorCount,
             null, null, null, null, null, null);
    }

    /**
     * Full constructor with execution context.
     */
    public SessionSummary(long sessionId, String workflowName, String overlayName,
                          String inventoryName, LocalDateTime startedAt,
                          LocalDateTime endedAt, int nodeCount, SessionStatus status,
                          int successCount, int failedCount, List<String> failedNodes,
                          int totalLogEntries, int errorCount,
                          String cwd, String gitCommit, String gitBranch,
                          String commandLine, String actorIacVersion, String actorIacCommit) {
        this.sessionId = sessionId;
        this.workflowName = workflowName;
        this.overlayName = overlayName;
        this.inventoryName = inventoryName;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.nodeCount = nodeCount;
        this.status = status;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.failedNodes = failedNodes;
        this.totalLogEntries = totalLogEntries;
        this.errorCount = errorCount;
        this.cwd = cwd;
        this.gitCommit = gitCommit;
        this.gitBranch = gitBranch;
        this.commandLine = commandLine;
        this.actorIacVersion = actorIacVersion;
        this.actorIacCommit = actorIacCommit;
    }

    public long getSessionId() { return sessionId; }
    public String getWorkflowName() { return workflowName; }
    public String getOverlayName() { return overlayName; }
    public String getInventoryName() { return inventoryName; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public int getNodeCount() { return nodeCount; }
    public SessionStatus getStatus() { return status; }
    public int getSuccessCount() { return successCount; }
    public int getFailedCount() { return failedCount; }
    public List<String> getFailedNodes() { return failedNodes; }
    public int getTotalLogEntries() { return totalLogEntries; }
    public int getErrorCount() { return errorCount; }

    // Execution context getters
    public String getCwd() { return cwd; }
    public String getGitCommit() { return gitCommit; }
    public String getGitBranch() { return gitBranch; }
    public String getCommandLine() { return commandLine; }
    public String getActorIacVersion() { return actorIacVersion; }
    public String getActorIacCommit() { return actorIacCommit; }

    public Duration getDuration() {
        if (startedAt == null || endedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, endedAt);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Session #").append(sessionId).append(": ").append(workflowName).append("\n");
        if (overlayName != null) {
            sb.append("  Overlay:  ").append(overlayName).append("\n");
        }
        if (inventoryName != null) {
            sb.append("  Inventory: ").append(inventoryName).append("\n");
        }
        sb.append("  Started:  ").append(formatTimestamp(startedAt)).append("\n");
        sb.append("  Ended:    ").append(formatTimestamp(endedAt)).append("\n");

        Duration d = getDuration();
        if (!d.isZero()) {
            long minutes = d.toMinutes();
            long seconds = d.toSecondsPart();
            sb.append("  Duration: ").append(minutes).append("m ").append(seconds).append("s\n");
        }

        sb.append("  Nodes:    ").append(nodeCount).append("\n");
        sb.append("  Status:   ").append(status).append("\n");
        sb.append("\n");
        sb.append("  Results:\n");
        sb.append("    SUCCESS: ").append(successCount).append(" nodes\n");
        sb.append("    FAILED:  ").append(failedCount).append(" nodes");
        if (failedNodes != null && !failedNodes.isEmpty()) {
            sb.append(" (").append(String.join(", ", failedNodes)).append(")");
        }
        sb.append("\n");
        sb.append("\n");
        sb.append("  Log lines: ").append(totalLogEntries).append(" (").append(errorCount).append(" errors)");
        return sb.toString();
    }

    /**
     * Formats a timestamp in ISO 8601 format with timezone.
     */
    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "N/A";
        }
        return timestamp.atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
    }
}
