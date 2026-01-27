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

package com.scivicslab.actoriac;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.workflow.ActorSystemAware;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
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
 * @author devteam@scivicslab.com
 * @since 2.12.2
 */
public class WorkflowReporter implements CallableByActionName, ActorSystemAware {

    private static final String CLASS_NAME = WorkflowReporter.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    /** Prefix for messages to be included in the final report. */
    private static final String REPORT_PREFIX = "%";

    private Connection connection;
    private IIActorSystem system;

    /** Pre-collected lines to be added at the beginning of the report. */
    private final List<String> preLines = new ArrayList<>();

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

        // Auto-initialize database connection from DistributedLogStore singleton
        if (this.connection == null) {
            DistributedLogStore logStore = DistributedLogStore.getInstance();
            if (logStore != null) {
                this.connection = logStore.getConnection();
                logger.info("WorkflowReporter: Auto-initialized database connection from DistributedLogStore");
            } else {
                logger.warning("WorkflowReporter: DistributedLogStore singleton not available");
            }
        }

        logger.exiting(CLASS_NAME, "setActorSystem");
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        logger.info("WorkflowReporter.callByActionName: actionName=" + actionName + ", args=" + args);
        try {
            ActionResult result = switch (actionName) {
                case "report" -> generateReport(args);
                case "transition-summary" -> transitionSummary(args);
                case "addLine" -> addLine(args);
                case "addWorkflowInfo" -> addWorkflowInfo(args);
                default -> {
                    logger.warning("WorkflowReporter: Unknown action: " + actionName);
                    yield new ActionResult(false, "Unknown action: " + actionName);
                }
            };
            logger.info("WorkflowReporter.callByActionName: result=" + result.isSuccess());
            return result;
        } catch (Exception e) {
            ActionResult errorResult = new ActionResult(false, "Error: " + e.getMessage());
            logger.logp(Level.WARNING, CLASS_NAME, "callByActionName", "Exception occurred", e);
            return errorResult;
        }
    }

    /**
     * Add a line to the report.
     *
     * <p>Lines added via this method will appear at the beginning of the
     * "Check Results" section in the report. This is useful for adding
     * workflow description or other contextual information.</p>
     *
     * <p>The argument is passed as a JSON array from the workflow engine,
     * so this method extracts the first element.</p>
     *
     * @param args the JSON array containing the line to add
     * @return ActionResult indicating success
     */
    private ActionResult addLine(String args) {
        logger.info("WorkflowReporter.addLine called with args: " + args);
        try {
            // Parse the JSON array to extract the actual line
            org.json.JSONArray jsonArray = new org.json.JSONArray(args);
            if (jsonArray.length() > 0) {
                String line = jsonArray.getString(0);
                preLines.add(line);
                logger.info("WorkflowReporter.addLine: added line: " + line);
            }
            return new ActionResult(true, "Line added");
        } catch (Exception e) {
            logger.warning("WorkflowReporter.addLine: exception: " + e.getMessage());
            // Fallback: treat args as plain string
            if (args != null && !args.isEmpty() && !args.equals("[]")) {
                preLines.add(args);
            }
            return new ActionResult(true, "Line added (fallback)");
        }
    }

    /**
     * Add workflow metadata (file, name, description) to the report.
     *
     * <p>This action reads the workflow YAML file and extracts the name and
     * description fields, adding them to the report header. The workflow
     * file path is obtained from nodeGroup.</p>
     *
     * @param args unused (workflow path is obtained from nodeGroup)
     * @return ActionResult indicating success or failure
     */
    private ActionResult addWorkflowInfo(String args) {
        logger.info("WorkflowReporter.addWorkflowInfo called");

        try {
            // Get workflow path from nodeGroup
            String workflowPath = getWorkflowPathFromNodeGroup();
            if (workflowPath == null) {
                logger.warning("WorkflowReporter.addWorkflowInfo: could not get workflow path from nodeGroup");
                return new ActionResult(false, "Could not retrieve workflow path from nodeGroup");
            }
            logger.info("WorkflowReporter.addWorkflowInfo: workflowPath=" + workflowPath);

            // Read and parse the YAML file
            Path path = Paths.get(workflowPath);
            if (!Files.exists(path)) {
                // Try relative to current working directory
                path = Paths.get(System.getProperty("user.dir"), workflowPath);
            }
            if (!Files.exists(path)) {
                // Workflow info without reading file - use only database info
                preLines.add("[Workflow Info]");
                preLines.add("  File: " + workflowPath);
                logger.info("WorkflowReporter.addWorkflowInfo: file not found, using DB info only");
                return new ActionResult(true, "Workflow info added (file not found, DB info only)");
            }

            Map<String, Object> yaml;
            try (InputStream is = Files.newInputStream(path)) {
                Yaml yamlParser = new Yaml();
                yaml = yamlParser.load(is);
            }

            if (yaml == null) {
                return new ActionResult(false, "Failed to parse workflow YAML");
            }

            // Extract name and description
            String name = (String) yaml.get("name");
            Object descObj = yaml.get("description");
            String description = descObj != null ? descObj.toString().trim() : null;

            // Add to preLines
            preLines.add("[Workflow Info]");
            preLines.add("  File: " + workflowPath);
            if (name != null) {
                preLines.add("  Name: " + name);
            }
            if (description != null) {
                preLines.add("");
                preLines.add("[Description]");
                // Indent each line of description
                for (String line : description.split("\n")) {
                    preLines.add("  " + line.trim());
                }
            }

            logger.info("WorkflowReporter.addWorkflowInfo: added workflow info for " + workflowPath);
            return new ActionResult(true, "Workflow info added");

        } catch (Exception e) {
            logger.warning("WorkflowReporter.addWorkflowInfo: exception: " + e.getMessage());
            return new ActionResult(false, "Failed to add workflow info: " + e.getMessage());
        }
    }

    /**
     * Retrieves workflow file path from session in database.
     */
    private String getWorkflowPathFromSession(long sessionId) throws SQLException {
        String sql = "SELECT workflow_name FROM sessions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("workflow_name");
                }
            }
        }
        return null;
    }

    /**
     * Retrieves workflow file path from nodeGroup actor.
     */
    private String getWorkflowPathFromNodeGroup() {
        if (system == null) {
            return null;
        }
        IIActorRef<?> nodeGroup = system.getIIActor("nodeGroup");
        if (nodeGroup == null) {
            return null;
        }
        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
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

            // Add pre-collected lines first
            if (!preLines.isEmpty()) {
                sb.append("\n");
                for (String line : preLines) {
                    sb.append(line).append("\n");
                }
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
        // Sort messages by hostname (messages are in format "hostname: [status] message")
        messages.sort(null);
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
