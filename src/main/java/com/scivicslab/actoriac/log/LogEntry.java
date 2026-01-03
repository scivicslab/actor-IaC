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

import java.time.LocalDateTime;

/**
 * Represents a single log entry.
 *
 * @author devteam@scivics-lab.com
 */
public class LogEntry {
    private final long id;
    private final long sessionId;
    private final LocalDateTime timestamp;
    private final String nodeId;
    private final String vertexName;
    private final String actionName;
    private final LogLevel level;
    private final String message;
    private final Integer exitCode;
    private final Long durationMs;

    public LogEntry(long id, long sessionId, LocalDateTime timestamp, String nodeId,
                    String vertexName, String actionName, LogLevel level, String message,
                    Integer exitCode, Long durationMs) {
        this.id = id;
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.nodeId = nodeId;
        this.vertexName = vertexName;
        this.actionName = actionName;
        this.level = level;
        this.message = message;
        this.exitCode = exitCode;
        this.durationMs = durationMs;
    }

    public long getId() { return id; }
    public long getSessionId() { return sessionId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getNodeId() { return nodeId; }
    public String getVertexName() { return vertexName; }
    public String getActionName() { return actionName; }
    public LogLevel getLevel() { return level; }
    public String getMessage() { return message; }
    public Integer getExitCode() { return exitCode; }
    public Long getDurationMs() { return durationMs; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timestamp.toString().replace("T", " ")).append("] ");
        sb.append(String.format("%-5s ", level));
        if (vertexName != null) {
            sb.append("[").append(vertexName);
            if (actionName != null) {
                sb.append(" -> ").append(actionName);
            }
            sb.append("] ");
        }
        sb.append(message);
        if (exitCode != null && exitCode != 0) {
            sb.append(" (exit=").append(exitCode).append(")");
        }
        if (durationMs != null) {
            sb.append(" [").append(durationMs).append("ms]");
        }
        return sb.toString();
    }
}
