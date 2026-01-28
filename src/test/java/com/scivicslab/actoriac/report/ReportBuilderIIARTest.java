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

package com.scivicslab.actoriac.report;

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
 * Tests for ReportBuilderIIAR.
 *
 * <p>ReportBuilderIIAR is an actor (extends IIActorRef) that wraps
 * the ReportBuilder POJO. Actions are defined via @Action annotations.</p>
 */
class ReportBuilderIIARTest {

    private ReportBuilderIIAR actor;
    private IIActorSystem testSystem;
    private TestActorRef testNodeGroup;
    private TestActorRef testOutputMultiplexer;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        // Setup test system first
        testSystem = new IIActorSystem("test-system");
        testNodeGroup = new TestActorRef("nodeGroup");
        testOutputMultiplexer = new TestActorRef("outputMultiplexer");

        testSystem.addIIActor(testNodeGroup);
        testSystem.addIIActor(testOutputMultiplexer);

        // Create POJO and wrap it with actor, passing system to constructor
        ReportBuilder builder = new ReportBuilder();
        actor = new ReportBuilderIIAR("reportBuilder", builder, testSystem);

        // Setup in-memory database
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:test_reportbuilder_iiar_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS sessions " +
                "(id BIGINT PRIMARY KEY, workflow_name VARCHAR(255), started_at TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS logs " +
                "(id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id BIGINT, " +
                "label VARCHAR(255), level VARCHAR(50), message TEXT, timestamp TIMESTAMP)");
            stmt.execute("INSERT INTO sessions (id, workflow_name, started_at) " +
                "VALUES (1, 'test-workflow.yaml', CURRENT_TIMESTAMP)");
        }

        actor.setConnection(connection);
    }

    // ========================================================================
    // Actor Structure Tests
    // ========================================================================

    @Nested
    @DisplayName("Actor Structure")
    class ActorStructureTests {

        @Test
        @DisplayName("ReportBuilderIIAR extends IIActorRef")
        void actorExtendsIIActorRef() {
            assertTrue(actor instanceof IIActorRef);
        }

        @Test
        @DisplayName("Actor name is set correctly")
        void actorNameIsCorrect() {
            assertEquals("reportBuilder", actor.getName());
        }

        @Test
        @DisplayName("Actor is registered in system")
        void actorIsRegisteredInSystem() {
            testSystem.addIIActor(actor);
            assertNotNull(testSystem.getIIActor("reportBuilder"));
        }
    }

    // ========================================================================
    // Basic Action Tests
    // ========================================================================

    @Nested
    @DisplayName("Basic Actions")
    class BasicActionTests {

        @Test
        @DisplayName("Unknown action returns failure")
        void unknownAction_returnsFalse() {
            ActionResult result = actor.callByActionName("unknownAction", "");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unknown action"));
        }

        @Test
        @DisplayName("report action with no sections returns header only")
        void report_noSections_returnsHeaderOnly() {
            ActionResult result = actor.callByActionName("report", "");

            assertTrue(result.isSuccess());
            assertTrue(result.getResult().contains("=== Workflow Execution Report ==="));
        }
    }

    // ========================================================================
    // addWorkflowInfo Tests
    // ========================================================================

    @Nested
    @DisplayName("addWorkflowInfo Action")
    class AddWorkflowInfoTests {

        @Test
        @DisplayName("addWorkflowInfo gets path from nodeGroup")
        void addWorkflowInfo_getsPathFromNodeGroup() {
            testNodeGroup.setActionResult("getWorkflowPath",
                new ActionResult(true, "/path/to/workflow.yaml"));

            ActionResult result = actor.callByActionName("addWorkflowInfo", "");

            assertTrue(result.isSuccess());
            assertTrue(testNodeGroup.wasActionCalled("getWorkflowPath"));
        }

        @Test
        @DisplayName("addWorkflowInfo includes path in report")
        void addWorkflowInfo_includesPathInReport() {
            testNodeGroup.setActionResult("getWorkflowPath",
                new ActionResult(true, "/path/to/my-workflow.yaml"));

            actor.callByActionName("addWorkflowInfo", "");
            ActionResult result = actor.callByActionName("report", "");

            assertTrue(result.isSuccess());
            assertTrue(result.getResult().contains("[Workflow Info]"));
            assertTrue(result.getResult().contains("/path/to/my-workflow.yaml"));
        }

        @Test
        @DisplayName("addWorkflowInfo fails without nodeGroup")
        void addWorkflowInfo_failsWithoutNodeGroup() {
            // Remove nodeGroup from system
            testSystem.removeIIActor("nodeGroup");

            ActionResult result = actor.callByActionName("addWorkflowInfo", "");

            assertFalse(result.isSuccess());
        }
    }

    // ========================================================================
    // addJsonStateSection Tests
    // ========================================================================

    @Nested
    @DisplayName("addJsonStateSection Action")
    class AddJsonStateSectionTests {

        @Test
        @DisplayName("addJsonStateSection adds section for actor")
        void addJsonStateSection_addsSection() {
            // Setup target actor with JsonState
            TestActorRef targetActor = new TestActorRef("node-localhost");
            targetActor.putJson("cluster.name", "test-cluster");
            targetActor.putJson("cluster.nodes", 3);

            testSystem.addIIActor(targetActor);

            JSONObject args = new JSONObject();
            args.put("actor", "node-localhost");
            ActionResult result = actor.callByActionName("addJsonStateSection", args.toString());

            assertTrue(result.isSuccess());

            // Verify section is in report
            ActionResult reportResult = actor.callByActionName("report", "");
            assertTrue(reportResult.getResult().contains("Collected Data (node-localhost)"));
            assertTrue(reportResult.getResult().contains("cluster"));
        }

        @Test
        @DisplayName("addJsonStateSection with path filters content")
        void addJsonStateSection_withPath_filtersContent() {
            TestActorRef targetActor = new TestActorRef("node-localhost");
            targetActor.putJson("cluster.name", "test-cluster");
            targetActor.putJson("other.data", "ignored");

            testSystem.addIIActor(targetActor);

            JSONObject args = new JSONObject();
            args.put("actor", "node-localhost");
            args.put("path", "cluster");
            actor.callByActionName("addJsonStateSection", args.toString());

            ActionResult reportResult = actor.callByActionName("report", "");
            assertTrue(reportResult.getResult().contains("name: test-cluster"));
        }

        @Test
        @DisplayName("addJsonStateSection fails for unknown actor")
        void addJsonStateSection_unknownActor_fails() {
            JSONObject args = new JSONObject();
            args.put("actor", "unknown-actor");
            ActionResult result = actor.callByActionName("addJsonStateSection", args.toString());

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("not found"));
        }
    }

    // ========================================================================
    // addTransitionSection Tests
    // ========================================================================

    @Nested
    @DisplayName("addTransitionSection Action")
    class AddTransitionSectionTests {

        @Test
        @DisplayName("addTransitionSection adds section from logs")
        void addTransitionSection_addsSectionFromLogs() throws Exception {
            // Insert transition logs
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO logs (session_id, label, level, message, timestamp) " +
                    "VALUES (1, '0 -> 1', 'INFO', 'Transition SUCCESS', CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO logs (session_id, label, level, message, timestamp) " +
                    "VALUES (1, '1 -> 2', 'INFO', 'Transition SUCCESS', CURRENT_TIMESTAMP)");
            }

            testNodeGroup.setActionResult("getSessionId", new ActionResult(true, "1"));

            ActionResult result = actor.callByActionName("addTransitionSection", "");

            assertTrue(result.isSuccess());

            ActionResult reportResult = actor.callByActionName("report", "");
            assertTrue(reportResult.getResult().contains("--- Transitions ---"));
            assertTrue(reportResult.getResult().contains("[✓] 0 -> 1"));
            assertTrue(reportResult.getResult().contains("[✓] 1 -> 2"));
            assertTrue(reportResult.getResult().contains("2 succeeded, 0 failed"));
        }

        @Test
        @DisplayName("addTransitionSection shows failures")
        void addTransitionSection_showsFailures() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO logs (session_id, label, level, message, timestamp) " +
                    "VALUES (1, '0 -> 1', 'INFO', 'Transition SUCCESS', CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO logs (session_id, label, level, message, timestamp) " +
                    "VALUES (1, '1 -> 2', 'ERROR', 'Transition FAILED - action failed', CURRENT_TIMESTAMP)");
            }

            testNodeGroup.setActionResult("getSessionId", new ActionResult(true, "1"));

            actor.callByActionName("addTransitionSection", "");
            ActionResult reportResult = actor.callByActionName("report", "");

            assertTrue(reportResult.getResult().contains("[✓] 0 -> 1"));
            assertTrue(reportResult.getResult().contains("[✗] 1 -> 2"));
            assertTrue(reportResult.getResult().contains("1 succeeded, 1 failed"));
        }
    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Full report with all sections")
        void fullReport_allSections() throws Exception {
            // Setup workflow info
            testNodeGroup.setActionResult("getWorkflowPath",
                new ActionResult(true, "/workflows/main-cluster-status.yaml"));
            testNodeGroup.setActionResult("getSessionId", new ActionResult(true, "1"));

            // Setup target actor with JsonState
            TestActorRef targetActor = new TestActorRef("node-localhost");
            targetActor.putJson("cluster.name", "prod-cluster");
            targetActor.putJson("cluster.nodes", 5);
            testSystem.addIIActor(targetActor);

            // Setup transition logs
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO logs (session_id, label, level, message, timestamp) " +
                    "VALUES (1, '0 -> 1', 'INFO', 'Transition SUCCESS', CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO logs (session_id, label, level, message, timestamp) " +
                    "VALUES (1, '1 -> 2', 'INFO', 'Transition SUCCESS', CURRENT_TIMESTAMP)");
            }

            // Build report
            actor.callByActionName("addWorkflowInfo", "");
            actor.callByActionName("addTransitionSection", "");
            actor.callByActionName("addJsonStateSection", "{\"actor\":\"node-localhost\"}");
            ActionResult result = actor.callByActionName("report", "");

            assertTrue(result.isSuccess());

            String report = result.getResult();

            // Verify order: WorkflowInfo (100) < Transitions (300) < JsonState (400)
            int workflowInfoIdx = report.indexOf("[Workflow Info]");
            int transitionsIdx = report.indexOf("--- Transitions ---");
            int collectedDataIdx = report.indexOf("--- Collected Data");

            assertTrue(workflowInfoIdx < transitionsIdx,
                "WorkflowInfo should come before Transitions");
            assertTrue(transitionsIdx < collectedDataIdx,
                "Transitions should come before Collected Data");

            // Verify content
            assertTrue(report.contains("main-cluster-status.yaml"));
            assertTrue(report.contains("[✓] 0 -> 1"));
            assertTrue(report.contains("prod-cluster"));
        }

        @Test
        @DisplayName("Report outputs to outputMultiplexer with correct format")
        void report_outputsToMultiplexer() {
            actor.callByActionName("report", "");

            assertTrue(testOutputMultiplexer.wasActionCalled("add"));
            String arg = testOutputMultiplexer.getLastArgs("add");

            assertNotNull(arg);
            JSONObject json = new JSONObject(arg);
            assertEquals("report-builder", json.getString("source"));
            assertEquals("plugin-result", json.getString("type"));
            assertTrue(json.getString("data").contains("=== Workflow Execution Report ==="));
        }
    }

    // ========================================================================
    // Test Double
    // ========================================================================

    /**
     * Test subclass of IIActorRef for testing.
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
            // Fall back to parent for JSON state operations
            return super.callByActionName(actionName, args);
        }
    }
}
