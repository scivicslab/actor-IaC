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

package com.scivicslab.actoriac.plugins.transitionviewer;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.workflow.ActorSystemAware;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ワークフロー実行時に記録されたTransition履歴を表示するプラグイン。
 *
 * <p>指定したアクター（ノードまたはNodeGroup）のTransition履歴を
 * データベースから取得し、人間が読みやすい形式で出力する。</p>
 *
 * <h2>アクション:</h2>
 * <ul>
 *   <li>{@code showTransitions} - 指定アクターのTransition履歴を表示</li>
 * </ul>
 *
 * <h2>使用例（ワークフロー）:</h2>
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ["ROOT", "transitionViewer", "com.scivicslab.actoriac.plugins.transitionviewer.TransitionViewerPlugin"]
 * - actor: transitionViewer
 *   method: showTransitions
 *   arguments:
 *     target: "node-localhost"
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class TransitionViewerPlugin implements CallableByActionName, ActorSystemAware {

    private static final String CLASS_NAME = TransitionViewerPlugin.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private IIActorSystem system;
    private Connection connection;

    /**
     * デフォルトコンストラクタ。loader.createChildで使用される。
     */
    public TransitionViewerPlugin() {
        initConnection();
    }

    private void initConnection() {
        DistributedLogStore logStore = DistributedLogStore.getInstance();
        if (logStore != null) {
            this.connection = logStore.getConnection();
            logger.fine("TransitionViewerPlugin: Initialized database connection");
        }
    }

    @Override
    public void setActorSystem(IIActorSystem system) {
        this.system = system;
        logger.fine("TransitionViewerPlugin: ActorSystem set");
    }

    /**
     * テスト用にデータベース接続を設定する。
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        logger.info("TransitionViewerPlugin.callByActionName: " + actionName);

        return switch (actionName) {
            case "showTransitions" -> showTransitions(args);
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    /**
     * 指定されたアクターのTransition履歴を表示する。
     *
     * @param args JSON形式の引数
     *   - target: アクター名（必須）
     *   - session: セッションID（省略時は最新）
     *   - includeChildren: trueの場合、配下ノードも含める
     */
    private ActionResult showTransitions(String args) {
        if (connection == null) {
            return new ActionResult(false, "Database connection not available");
        }

        String target;
        long sessionId = -1;
        boolean includeChildren = false;

        try {
            JSONObject json = new JSONObject(args);
            target = json.getString("target");
            sessionId = json.optLong("session", -1);
            includeChildren = json.optBoolean("includeChildren", false);
        } catch (Exception e) {
            return new ActionResult(false, "Invalid arguments: " + e.getMessage() +
                ". Expected: {\"target\": \"actor-name\", \"session\": optional, \"includeChildren\": optional}");
        }

        // セッションIDが指定されていなければnodeGroupから取得
        if (sessionId < 0) {
            try {
                sessionId = getSessionIdFromNodeGroup();
            } catch (Exception e) {
                return new ActionResult(false, "Could not get session ID: " + e.getMessage());
            }
        }

        try {
            String output;
            if (includeChildren && "nodeGroup".equals(target)) {
                output = buildAggregatedOutput(sessionId);
            } else {
                output = buildSingleActorOutput(target, sessionId);
            }

            outputToMultiplexer(output);
            return new ActionResult(true, output);

        } catch (Exception e) {
            logger.warning("TransitionViewerPlugin.showTransitions: " + e.getMessage());
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * 単一アクターのTransition履歴を構築する。
     */
    private String buildSingleActorOutput(String source, long sessionId) throws Exception {
        List<TransitionEntry> entries = queryTransitions(source, sessionId);
        String workflowName = querySessionWorkflowName(sessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Transition History ===\n");
        if (workflowName != null && !workflowName.isEmpty()) {
            sb.append("Workflow: ").append(workflowName).append("\n");
        }
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Target: ").append(source).append("\n\n");

        int succeeded = 0;
        int failed = 0;

        for (TransitionEntry entry : entries) {
            sb.append(entry.success ? "o " : "x ");
            sb.append("[").append(entry.timestamp).append("] ");
            sb.append(entry.label);
            if (entry.note != null && !entry.note.isEmpty()) {
                sb.append(" [").append(entry.note).append("]");
            }
            if (entry.success) {
                sb.append("\n");
                succeeded++;
            } else {
                if (entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                    sb.append(" ").append(entry.errorMessage);
                }
                sb.append("\n");
                failed++;
            }
        }

        sb.append("\nSummary: ").append(entries.size()).append(" transitions, ");
        sb.append(succeeded).append(" succeeded, ").append(failed).append(" failed");

        return sb.toString();
    }

    /**
     * NodeGroupと配下ノードの集約出力を構築する。
     */
    private String buildAggregatedOutput(long sessionId) throws Exception {
        // セッション内の全アクターを取得
        List<String> sources = queryDistinctSources(sessionId);
        String workflowName = querySessionWorkflowName(sessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Transition History ===\n");
        if (workflowName != null && !workflowName.isEmpty()) {
            sb.append("Workflow: ").append(workflowName).append("\n");
        }
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Target: nodeGroup (with children)\n");

        int totalTransitions = 0;
        int totalSucceeded = 0;
        int totalFailed = 0;

        for (String source : sources) {
            List<TransitionEntry> entries = queryTransitions(source, sessionId);
            if (entries.isEmpty()) continue;

            sb.append("\n[").append(source).append("]\n");

            for (TransitionEntry entry : entries) {
                sb.append("  ").append(entry.success ? "o " : "x ");
                sb.append("[").append(entry.timestamp).append("] ");
                sb.append(entry.label);
                if (entry.note != null && !entry.note.isEmpty()) {
                    sb.append(" [").append(entry.note).append("]");
                }
                if (entry.success) {
                    sb.append("\n");
                    totalSucceeded++;
                } else {
                    if (entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                        sb.append(" ").append(entry.errorMessage);
                    }
                    sb.append("\n");
                    totalFailed++;
                }
                totalTransitions++;
            }
        }

        sb.append("\nSummary: ").append(totalTransitions).append(" transitions, ");
        sb.append(totalSucceeded).append(" succeeded, ").append(totalFailed).append(" failed");

        return sb.toString();
    }

    /**
     * 指定アクターのTransitionログをクエリする。
     */
    private List<TransitionEntry> queryTransitions(String source, long sessionId) throws Exception {
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

                    // messageから状態遷移とnoteを抽出
                    String[] transitionAndNote = extractTransitionAndNote(message);
                    String transition = transitionAndNote[0];
                    String note = transitionAndNote[1];

                    // labelが意味のある状態遷移でない場合はmessageから抽出した値を使用
                    String displayLabel = (label != null && label.contains("->")) ? label : transition;

                    String timeStr = ts.toLocalDateTime().format(TIME_FORMAT);
                    entries.add(new TransitionEntry(timeStr, displayLabel, note, success, errorMessage));
                }
            }
        }

        return entries;
    }

    /**
     * セッション内でTransitionを記録した全アクターを取得する。
     */
    private List<String> queryDistinctSources(long sessionId) throws Exception {
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
     * セッションIDからワークフロー名を取得する。
     */
    private String querySessionWorkflowName(long sessionId) throws Exception {
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

    private long getSessionIdFromNodeGroup() {
        if (system == null) {
            throw new RuntimeException("ActorSystem not set");
        }

        IIActorRef<?> nodeGroup = system.getIIActor("nodeGroup");
        if (nodeGroup == null) {
            throw new RuntimeException("nodeGroup not found");
        }

        ActionResult result = nodeGroup.callByActionName("getSessionId", "");
        if (!result.isSuccess()) {
            throw new RuntimeException("Could not get session ID from nodeGroup");
        }

        return Long.parseLong(result.getResult());
    }

    /**
     * messageから状態遷移とnoteを抽出する。
     * 例: "Transition SUCCESS: 0 -> 1" → ["0 -> 1", ""]
     * 例: "Transition SUCCESS: 0 -> 1 [Collect info]" → ["0 -> 1", "Collect info"]
     * 例: "[node-localhost] Transition SUCCESS: 1 -> 2 [Process data]" → ["1 -> 2", "Process data"]
     *
     * @return String[2] where [0]=transition, [1]=note
     */
    private String[] extractTransitionAndNote(String message) {
        if (message == null) return new String[]{"unknown", ""};

        // "Transition SUCCESS: X -> Y" または "Transition FAILED: X -> Y" のパターン
        int idx = message.indexOf("Transition ");
        if (idx < 0) return new String[]{"unknown", ""};

        String afterTransition = message.substring(idx + "Transition ".length());
        // "SUCCESS: 0 -> 1 [note]" or "FAILED: 0 -> 1 [note] - error message"
        int colonIdx = afterTransition.indexOf(": ");
        if (colonIdx < 0) return new String[]{"unknown", ""};

        String statesPart = afterTransition.substring(colonIdx + 2);

        // noteを抽出 [xxx]
        String note = "";
        int bracketStart = statesPart.indexOf(" [");
        int bracketEnd = statesPart.indexOf("]");
        if (bracketStart > 0 && bracketEnd > bracketStart) {
            note = statesPart.substring(bracketStart + 2, bracketEnd);
            statesPart = statesPart.substring(0, bracketStart);
        }

        // " - error message" があれば除去
        int dashIdx = statesPart.indexOf(" - ");
        if (dashIdx > 0) {
            statesPart = statesPart.substring(0, dashIdx);
        }

        return new String[]{statesPart.trim(), note};
    }

    /**
     * 後方互換性のためのラッパー
     */
    private String extractTransition(String message) {
        return extractTransitionAndNote(message)[0];
    }

    private void outputToMultiplexer(String data) {
        if (system == null) return;

        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) return;

        JSONObject arg = new JSONObject();
        arg.put("source", "transition-viewer");
        arg.put("type", "plugin-result");
        arg.put("data", data);
        multiplexer.callByActionName("add", arg.toString());
    }

    /**
     * Transition履歴エントリ。
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
