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

package com.scivicslab.actoriac.report.sections.basic;

import com.scivicslab.actoriac.report.SectionBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * POJO section builder that outputs workflow transition history.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link TransitionHistorySectionIIAR} to expose as an actor.</p>
 *
 * <p>Retrieves transition logs from the database and displays them
 * in a human-readable format with success/failure status.</p>
 *
 * <h2>Output example:</h2>
 * <pre>
 * [Transition History: nodeGroup]
 * o [2026-01-30 10:15:23] 0 -> 1 [Initialize]
 * o [2026-01-30 10:15:24] 1 -> 2 [Collect data]
 * x [2026-01-30 10:15:25] 2 -> 3 [Process] Connection refused
 *
 * Summary: 3 transitions, 2 succeeded, 1 failed
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class TransitionHistorySection implements SectionBuilder {

    private static final Logger logger = Logger.getLogger(TransitionHistorySection.class.getName());
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Connection connection;
    private long sessionId = -1;
    private String targetActorName;
    private boolean includeChildren = false;

    /**
     * Sets the database connection for log queries.
     *
     * @param connection the JDBC connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sets the session ID to query logs from.
     *
     * @param sessionId the session ID
     */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Sets the target actor name to display transitions for.
     *
     * @param targetActorName the target actor name (e.g., "nodeGroup", "node-server1")
     */
    public void setTargetActorName(String targetActorName) {
        this.targetActorName = targetActorName;
    }

    /**
     * Sets whether to include child nodes in the output.
     *
     * <p>When true and target is "nodeGroup", transitions from all
     * child nodes are also included in the output.</p>
     *
     * @param includeChildren true to include children
     */
    public void setIncludeChildren(boolean includeChildren) {
        this.includeChildren = includeChildren;
    }

    @Override
    public String generate() {
        if (connection == null || sessionId < 0) {
            logger.warning("TransitionHistorySection: connection or sessionId not set");
            return "";
        }

        if (targetActorName == null || targetActorName.isEmpty()) {
            targetActorName = "nodeGroup";  // Default target
        }

        try {
            if (includeChildren && "nodeGroup".equals(targetActorName)) {
                return buildAggregatedOutput();
            } else {
                return buildSingleActorOutput();
            }
        } catch (Exception e) {
            logger.warning("TransitionHistorySection: error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Builds output for a single actor.
     */
    private String buildSingleActorOutput() throws Exception {
        List<TransitionEntry> entries = queryTransitions(targetActorName);
        if (entries.isEmpty()) {
            return "";  // No transitions, skip this section
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Transition History: ").append(targetActorName).append("]\n");

        int succeeded = 0;
        int failed = 0;

        for (TransitionEntry entry : entries) {
            sb.append(entry.success ? "o " : "x ");
            sb.append("[").append(entry.timestamp).append("] ");
            sb.append(entry.label);
            if (entry.note != null && !entry.note.isEmpty()) {
                sb.append(" [").append(entry.note).append("]");
            }
            if (!entry.success && entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                sb.append(" ").append(entry.errorMessage);
            }
            sb.append("\n");

            if (entry.success) {
                succeeded++;
            } else {
                failed++;
            }
        }

        sb.append("\nSummary: ").append(entries.size()).append(" transitions, ");
        sb.append(succeeded).append(" succeeded, ").append(failed).append(" failed\n");

        return sb.toString();
    }

    /**
     * Builds aggregated output for nodeGroup and all children.
     */
    private String buildAggregatedOutput() throws Exception {
        List<String> sources = queryDistinctSources();
        if (sources.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Transition History: nodeGroup (with children)]\n");

        int totalTransitions = 0;
        int totalSucceeded = 0;
        int totalFailed = 0;

        for (String source : sources) {
            List<TransitionEntry> entries = queryTransitions(source);
            if (entries.isEmpty()) continue;

            sb.append("\n  [").append(source).append("]\n");

            for (TransitionEntry entry : entries) {
                sb.append("  ").append(entry.success ? "o " : "x ");
                sb.append("[").append(entry.timestamp).append("] ");
                sb.append(entry.label);
                if (entry.note != null && !entry.note.isEmpty()) {
                    sb.append(" [").append(entry.note).append("]");
                }
                if (!entry.success && entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                    sb.append(" ").append(entry.errorMessage);
                }
                sb.append("\n");

                totalTransitions++;
                if (entry.success) {
                    totalSucceeded++;
                } else {
                    totalFailed++;
                }
            }
        }

        sb.append("\nSummary: ").append(totalTransitions).append(" transitions, ");
        sb.append(totalSucceeded).append(" succeeded, ").append(totalFailed).append(" failed\n");

        return sb.toString();
    }

    /**
     * Queries transition logs for a specific actor.
     */
    private List<TransitionEntry> queryTransitions(String source) throws Exception {
        List<TransitionEntry> entries = new ArrayList<>();

        String sql = "SELECT timestamp, label, level, message FROM logs " +
                     "WHERE session_id = ? AND node_id = ? AND message LIKE '%Transition %' " +
                     "ORDER BY timestamp";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            ps.setString(2, source);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("timestamp");
                    String label = rs.getString("label");
                    String message = rs.getString("message");

                    boolean success = message.contains("SUCCESS");
                    String errorMessage = null;
                    if (!success) {
                        int dashIdx = message.indexOf(" - ");
                        if (dashIdx > 0) {
                            errorMessage = message.substring(dashIdx + 3);
                        }
                    }

                    String[] transitionAndNote = extractTransitionAndNote(message);
                    String transition = transitionAndNote[0];
                    String note = transitionAndNote[1];

                    String displayLabel = (label != null && label.contains("->")) ? label : transition;
                    String timeStr = ts.toLocalDateTime().format(TIME_FORMAT);

                    entries.add(new TransitionEntry(timeStr, displayLabel, note, success, errorMessage));
                }
            }
        }

        return entries;
    }

    /**
     * Queries distinct actor sources that have transition logs.
     */
    private List<String> queryDistinctSources() throws Exception {
        List<String> sources = new ArrayList<>();

        String sql = "SELECT DISTINCT node_id FROM logs " +
                     "WHERE session_id = ? AND message LIKE '%Transition %' " +
                     "ORDER BY node_id";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sources.add(rs.getString("node_id"));
                }
            }
        }

        return sources;
    }

    /**
     * Extracts transition and note from message.
     *
     * @return String[2] where [0]=transition, [1]=note
     */
    private String[] extractTransitionAndNote(String message) {
        if (message == null) return new String[]{"unknown", ""};

        int idx = message.indexOf("Transition ");
        if (idx < 0) return new String[]{"unknown", ""};

        String afterTransition = message.substring(idx + "Transition ".length());
        int colonIdx = afterTransition.indexOf(": ");
        if (colonIdx < 0) return new String[]{"unknown", ""};

        String statesPart = afterTransition.substring(colonIdx + 2);

        // Extract note [xxx]
        String note = "";
        int bracketStart = statesPart.indexOf(" [");
        int bracketEnd = statesPart.indexOf("]");
        if (bracketStart > 0 && bracketEnd > bracketStart) {
            note = statesPart.substring(bracketStart + 2, bracketEnd);
            statesPart = statesPart.substring(0, bracketStart);
        }

        // Remove " - error message" if present
        int dashIdx = statesPart.indexOf(" - ");
        if (dashIdx > 0) {
            statesPart = statesPart.substring(0, dashIdx);
        }

        return new String[]{statesPart.trim(), note};
    }

    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }

    /**
     * Internal class to hold transition entry data.
     */
    private static class TransitionEntry {
        final String timestamp;
        final String label;
        final String note;
        final boolean success;
        final String errorMessage;

        TransitionEntry(String timestamp, String label, String note, boolean success, String errorMessage) {
            this.timestamp = timestamp;
            this.label = label;
            this.note = note;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
