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

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransitionViewerPluginのテスト。
 */
class TransitionViewerPluginTest {

    private TransitionViewerPlugin plugin;
    private IIActorSystem testSystem;
    private TestActorRef testNodeGroup;
    private TestActorRef testOutputMultiplexer;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        testSystem = new IIActorSystem("test-system");
        testNodeGroup = new TestActorRef("nodeGroup");
        testOutputMultiplexer = new TestActorRef("outputMultiplexer");

        testSystem.addIIActor(testNodeGroup);
        testSystem.addIIActor(testOutputMultiplexer);

        plugin = new TransitionViewerPlugin();
        plugin.setActorSystem(testSystem);

        // Setup in-memory database
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:test_transitionviewer_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS logs " +
                "(id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id BIGINT, " +
                "node_id VARCHAR(255), label VARCHAR(255), level VARCHAR(50), " +
                "message TEXT, timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sessions " +
                "(id BIGINT PRIMARY KEY, workflow_name VARCHAR(255), " +
                "started_at TIMESTAMP, ended_at TIMESTAMP)");
            // Insert test session
            stmt.execute("INSERT INTO sessions (id, workflow_name) VALUES (1, 'test-workflow.yaml')");
        }

        plugin.setConnection(connection);
    }

    @Nested
    @DisplayName("showTransitions - 単一アクター")
    class SingleActorTests {

        @Test
        @DisplayName("指定アクターのTransition履歴を表示する")
        void showTransitions_singleActor() throws Exception {
            // Arrange
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '0 -> 1', 'INFO', 'Transition SUCCESS: 0 -> 1')");
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '1 -> 2', 'INFO', 'Transition SUCCESS: 1 -> 2')");
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '2 -> end', 'INFO', 'Transition SUCCESS: 2 -> end')");
            }

            JSONObject args = new JSONObject();
            args.put("target", "node-localhost");
            args.put("session", 1);

            // Act
            ActionResult result = plugin.callByActionName("showTransitions", args.toString());

            // Assert
            assertTrue(result.isSuccess());
            String output = result.getResult();
            assertTrue(output.contains("Target: node-localhost"));
            assertTrue(output.contains("Session: 1"));
            assertTrue(output.contains("o ["));
            assertTrue(output.contains("0 -> 1"));
            assertTrue(output.contains("1 -> 2"));
            assertTrue(output.contains("2 -> end"));
            assertTrue(output.contains("3 transitions, 3 succeeded, 0 failed"));
        }

        @Test
        @DisplayName("失敗したTransitionを表示する")
        void showTransitions_withFailure() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '0 -> 1', 'INFO', 'Transition SUCCESS: 0 -> 1')");
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '1 -> 2', 'WARN', 'Transition FAILED: 1 -> 2 - connection timeout')");
            }

            JSONObject args = new JSONObject();
            args.put("target", "node-localhost");
            args.put("session", 1);

            ActionResult result = plugin.callByActionName("showTransitions", args.toString());

            assertTrue(result.isSuccess());
            String output = result.getResult();
            assertTrue(output.contains("o ["));
            assertTrue(output.contains("x ["));
            assertTrue(output.contains("connection timeout"));
            assertTrue(output.contains("1 succeeded, 1 failed"));
        }

        @Test
        @DisplayName("Transitionがない場合は空の結果を返す")
        void showTransitions_empty() throws Exception {
            JSONObject args = new JSONObject();
            args.put("target", "node-localhost");
            args.put("session", 1);

            ActionResult result = plugin.callByActionName("showTransitions", args.toString());

            assertTrue(result.isSuccess());
            String output = result.getResult();
            assertTrue(output.contains("0 transitions, 0 succeeded, 0 failed"));
        }
    }

    @Nested
    @DisplayName("showTransitions - NodeGroup集約")
    class AggregatedTests {

        @Test
        @DisplayName("NodeGroupと配下ノードを集約表示する")
        void showTransitions_aggregated() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                // nodeGroupのTransition
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'nodeGroup', '0 -> 1', 'INFO', 'Transition SUCCESS: 0 -> 1')");
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'nodeGroup', '1 -> 2', 'INFO', 'Transition SUCCESS: 1 -> 2')");

                // node-localhostのTransition
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '0 -> 1', 'INFO', 'Transition SUCCESS: 0 -> 1')");
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '1 -> end', 'INFO', 'Transition SUCCESS: 1 -> end')");

                // node-server1のTransition（失敗あり）
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-server1', '0 -> 1', 'INFO', 'Transition SUCCESS: 0 -> 1')");
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-server1', '1 -> 2', 'WARN', 'Transition FAILED: 1 -> 2 - timeout')");
            }

            JSONObject args = new JSONObject();
            args.put("target", "nodeGroup");
            args.put("session", 1);
            args.put("includeChildren", true);

            ActionResult result = plugin.callByActionName("showTransitions", args.toString());

            assertTrue(result.isSuccess());
            String output = result.getResult();
            assertTrue(output.contains("Target: nodeGroup (with children)"));
            assertTrue(output.contains("[nodeGroup]"));
            assertTrue(output.contains("[node-localhost]"));
            assertTrue(output.contains("[node-server1]"));
            assertTrue(output.contains("6 transitions, 5 succeeded, 1 failed"));
        }
    }

    @Nested
    @DisplayName("エラーケース")
    class ErrorTests {

        @Test
        @DisplayName("不正な引数でエラーを返す")
        void showTransitions_invalidArgs() {
            ActionResult result = plugin.callByActionName("showTransitions", "invalid json");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Invalid arguments"));
        }

        @Test
        @DisplayName("不明なアクションでエラーを返す")
        void unknownAction() {
            ActionResult result = plugin.callByActionName("unknownAction", "{}");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unknown action"));
        }
    }

    @Nested
    @DisplayName("outputMultiplexer出力")
    class OutputTests {

        @Test
        @DisplayName("結果をoutputMultiplexerに出力する")
        void showTransitions_outputsToMultiplexer() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO logs (session_id, node_id, label, level, message) " +
                    "VALUES (1, 'node-localhost', '0 -> 1', 'INFO', 'Transition SUCCESS: 0 -> 1')");
            }

            JSONObject args = new JSONObject();
            args.put("target", "node-localhost");
            args.put("session", 1);

            plugin.callByActionName("showTransitions", args.toString());

            assertTrue(testOutputMultiplexer.wasActionCalled("add"));
            String addArg = testOutputMultiplexer.getLastArgs("add");
            JSONObject json = new JSONObject(addArg);
            assertEquals("transition-viewer", json.getString("source"));
            assertEquals("plugin-result", json.getString("type"));
            assertTrue(json.getString("data").contains("Transition History"));
        }
    }

    /**
     * テスト用のIIActorRefサブクラス。
     */
    static class TestActorRef extends IIActorRef<Object> {
        private final Map<String, ActionResult> actionResults = new HashMap<>();
        private final Map<String, Boolean> actionsCalled = new HashMap<>();
        private final Map<String, String> lastArgs = new HashMap<>();

        TestActorRef(String name) {
            super(name, new Object());
        }

        void setActionResult(String actionName, ActionResult result) {
            actionResults.put(actionName, result);
        }

        boolean wasActionCalled(String actionName) {
            return actionsCalled.getOrDefault(actionName, false);
        }

        String getLastArgs(String actionName) {
            return lastArgs.get(actionName);
        }

        @Override
        public ActionResult callByActionName(String actionName, String args) {
            actionsCalled.put(actionName, true);
            lastArgs.put(actionName, args);
            if (actionResults.containsKey(actionName)) {
                return actionResults.get(actionName);
            }
            return new ActionResult(true, "OK");
        }
    }
}
