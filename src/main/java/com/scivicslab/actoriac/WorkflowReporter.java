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

package com.scivicslab.actoriac;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.workflow.ActorSystemAware;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import org.json.JSONObject;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Workflow execution reporter for actor-IaC.
 *
 * <p>Aggregates workflow execution results and generates final reports.
 * Messages starting with '%' prefix are collected and displayed in the
 * final report, enabling simple check/status reporting from workflows.</p>
 *
 * <h2>Usage in workflows:</h2>
 * <pre>
 * - actor: this
 *   method: executeCommand
 *   arguments:
 *     - |
 *       if command -v node > /dev/null; then
 *         echo "%[OK] Node.js: $(node --version)"
 *       else
 *         echo "%[ERROR] Node.js: not found"
 *       fi
 * </pre>
 *
 * <h2>Actions:</h2>
 * <ul>
 *   <li>{@code report} - Generate workflow execution report. Args: sessionId (optional)</li>
 *   <li>{@code transition-summary} - Show transition success/failure summary. Args: sessionId</li>
 * </ul>
 *
 * <h2>Report Output Example:</h2>
 * <pre>
 * === Workflow Execution Report ===
 * Session #42 | Workflow: DocumentDeployWorkflow | Status: COMPLETED
 *
 * --- Check Results ---
 * [OK] Node.js: v18.0.0
 * [OK] yarn: 1.22.19
 * [ERROR] Maven: not found
 *
 * --- Transitions ---
 * [✓] check-doclist
 * [✓] detect-changes
 * [✗] build-docs: yarn not found
 *
 * === Result: FAILED ===
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.2
 */
public class WorkflowReporter implements CallableByActionName, ActorSystemAware {

    private static final String CLASS_NAME = WorkflowReporter.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    /** Prefix for messages to be included in the final report. */
    private static final String REPORT_PREFIX = "%";

    private Connection connection;
    private IIActorSystem system;

    /**
     * Sets the database connection for log queries.
     *
     * @param connection the JDBC connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void setActorSystem(IIActorSystem system) {
        logger.entering(CLASS_NAME, "setActorSystem", system);
        this.system = system;
        logger.exiting(CLASS_NAME, "setActorSystem");
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        logger.entering(CLASS_NAME, "callByActionName", new Object[]{actionName, args});
        try {
            ActionResult result = switch (actionName) {
                case "report" -> generateReport(args);
                case "transition-summary" -> transitionSummary(args);
                default -> new ActionResult(false, "Unknown action: " + actionName);
            };
            logger.exiting(CLASS_NAME, "callByActionName", result);
            return result;
        } catch (Exception e) {
            ActionResult errorResult = new ActionResult(false, "Error: " + e.getMessage());
            logger.logp(Level.WARNING, CLASS_NAME, "callByActionName", "Exception occurred", e);
            logger.exiting(CLASS_NAME, "callByActionName", errorResult);
            return errorResult;
        }
    }

    /**
     * Generate workflow execution report.
     *
     * <p>Collects messages starting with '%' prefix and generates a summary report.</p>
     *
     * @param sessionIdStr session ID to report on, or empty for auto-retrieval
     */
    private ActionResult generateReport(String sessionIdStr) {
        logger.entering(CLASS_NAME, "generateReport", sessionIdStr);
        if (connection == null) {
            return new ActionResult(false, "Not connected. Database connection not set.");
        }

        try {
            long sessionId = resolveSessionId(sessionIdStr);

            StringBuilder sb = new StringBuilder();
            sb.append("=== Workflow Execution Report ===\n");

            // Get session info
            String sessionInfo = getSessionInfo(sessionId);
            if (sessionInfo != null) {
                sb.append(sessionInfo).append("\n");
            }

            // Get messages with % prefix
            List<String> reportMessages = getReportMessages(sessionId);
            if (!reportMessages.isEmpty()) {
                sb.append("\n--- Check Results ---\n");
                for (String msg : reportMessages) {
                    sb.append(msg).append("\n");
                }
            }

            // Get transition summary
            String transitionInfo = buildTransitionSummary(sessionId);
            if (transitionInfo != null) {
                sb.append("\n--- Transitions ---\n");
                sb.append(transitionInfo);
            }

            // Get final status
            String finalStatus = getFinalStatus(sessionId);
            sb.append("\n").append(finalStatus);

            String result = sb.toString();
            reportToMultiplexer(result);
            return new ActionResult(true, result);

        } catch (Exception e) {
            return new ActionResult(false, "Report generation failed: " + e.getMessage());
        }
    }

    /**
     * Get session information.
     */
    private String getSessionInfo(long sessionId) throws SQLException {
        String sql = "SELECT workflow_name, overlay_name, status, started_at, ended_at " +
                     "FROM sessions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String workflow = rs.getString("workflow_name");
                    String overlay = rs.getString("overlay_name");
                    String status = rs.getString("status");
                    Timestamp started = rs.getTimestamp("started_at");
                    Timestamp ended = rs.getTimestamp("ended_at");

                    StringBuilder sb = new StringBuilder();
                    sb.append("Session #").append(sessionId);
                    if (workflow != null) sb.append(" | Workflow: ").append(workflow);
                    if (overlay != null) sb.append(" | Overlay: ").append(overlay);
                    sb.append(" | Status: ").append(status);
                    if (started != null) sb.append("\nStarted: ").append(started);
                    if (ended != null) sb.append(" | Ended: ").append(ended);
                    return sb.toString();
                }
            }
        }
        return null;
    }

    /**
     * Get messages with % prefix from logs.
     */
    private List<String> getReportMessages(long sessionId) throws SQLException {
        List<String> messages = new ArrayList<>();

        String sql = "SELECT message FROM logs WHERE session_id = ? ORDER BY timestamp";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String message = rs.getString("message");
                    if (message != null) {
                        // Extract lines starting with %
                        for (String line : message.split("\n")) {
                            String trimmed = line.trim();
                            // Handle prefixes like [node-xxx] %message
                            String cleaned = trimmed.replaceFirst("^\\[node-[^\\]]+\\]\\s*", "");
                            if (cleaned.startsWith(REPORT_PREFIX)) {
                                messages.add(cleaned.substring(1).trim());
                            }
                        }
                    }
                }
            }
        }
        return messages;
    }

    /**
     * Build transition summary.
     */
    private String buildTransitionSummary(long sessionId) throws SQLException {
        String sql = "SELECT label, level, message FROM logs " +
                     "WHERE session_id = ? AND message LIKE 'Transition %' " +
                     "ORDER BY timestamp";

        StringBuilder sb = new StringBuilder();
        int success = 0;
        int failed = 0;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String label = rs.getString("label");
                    String level = rs.getString("level");
                    String message = rs.getString("message");

                    boolean isSuccess = message.contains("SUCCESS");
                    if (isSuccess) {
                        success++;
                        sb.append("[✓] ").append(label != null ? label : "unknown").append("\n");
                    } else {
                        failed++;
                        sb.append("[✗] ").append(label != null ? label : "unknown");
                        // Extract failure reason
                        int dashIdx = message.indexOf(" - ");
                        if (dashIdx > 0) {
                            sb.append(": ").append(message.substring(dashIdx + 3));
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        if (success == 0 && failed == 0) {
            return null;
        }

        sb.append("\nSummary: ").append(success).append(" succeeded, ").append(failed).append(" failed");
        return sb.toString();
    }

    /**
     * Get final session status.
     */
    private String getFinalStatus(long sessionId) throws SQLException {
        String sql = "SELECT status FROM sessions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    if ("COMPLETED".equals(status)) {
                        return "=== Result: SUCCESS ===";
                    } else if ("FAILED".equals(status)) {
                        return "=== Result: FAILED ===";
                    } else {
                        return "=== Result: " + status + " ===";
                    }
                }
            }
        }
        return "=== Result: UNKNOWN ===";
    }

    /**
     * Show transition success/failure summary.
     */
    private ActionResult transitionSummary(String sessionIdStr) {
        logger.entering(CLASS_NAME, "transitionSummary", sessionIdStr);
        if (connection == null) {
            return new ActionResult(false, "Not connected. Database connection not set.");
        }

        try {
            long sessionId = resolveSessionId(sessionIdStr);
            String summary = buildTransitionSummary(sessionId);
            if (summary == null) {
                summary = "No transition data found for session " + sessionId;
            }
            reportToMultiplexer(summary);
            return new ActionResult(true, summary);
        } catch (Exception e) {
            return new ActionResult(false, "Query failed: " + e.getMessage());
        }
    }

    /**
     * Resolve session ID from argument or auto-retrieve from nodeGroup.
     */
    private long resolveSessionId(String sessionIdStr) throws NumberFormatException {
        if (sessionIdStr == null || sessionIdStr.trim().isEmpty() || sessionIdStr.equals("[]")) {
            String autoSessionId = getSessionIdFromNodeGroup();
            if (autoSessionId == null) {
                throw new NumberFormatException("Session ID not specified and could not retrieve from nodeGroup");
            }
            return Long.parseLong(autoSessionId);
        }
        return Long.parseLong(sessionIdStr.trim());
    }

    /**
     * Retrieves session ID from nodeGroup actor.
     */
    private String getSessionIdFromNodeGroup() {
        if (system == null) {
            return null;
        }
        IIActorRef<?> nodeGroup = system.getIIActor("nodeGroup");
        if (nodeGroup == null) {
            return null;
        }
        ActionResult result = nodeGroup.callByActionName("getSessionId", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    /**
     * Report result to outputMultiplexer.
     */
    private void reportToMultiplexer(String data) {
        if (system == null) {
            return;
        }

        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) {
            return;
        }

        JSONObject arg = new JSONObject();
        arg.put("source", "workflow-reporter");
        arg.put("type", "plugin-result");
        arg.put("data", data);
        multiplexer.callByActionName("add", arg.toString());
    }
}
