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

    @BeforeEach
    void setUp() {
        // Setup test system first
        testSystem = new IIActorSystem("test-system");
        testNodeGroup = new TestActorRef("nodeGroup");
        testOutputMultiplexer = new TestActorRef("outputMultiplexer");

        testSystem.addIIActor(testNodeGroup);
        testSystem.addIIActor(testOutputMultiplexer);

        // Create POJO and wrap it with actor, passing system to constructor
        ReportBuilder builder = new ReportBuilder();
        actor = new ReportBuilderIIAR("reportBuilder", builder, testSystem);
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
    // Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Full report with all sections")
        void fullReport_allSections() {
            // Setup workflow info
            testNodeGroup.setActionResult("getWorkflowPath",
                new ActionResult(true, "/workflows/main-cluster-status.yaml"));

            // Setup target actor with JsonState
            TestActorRef targetActor = new TestActorRef("node-localhost");
            targetActor.putJson("cluster.name", "prod-cluster");
            targetActor.putJson("cluster.nodes", 5);
            testSystem.addIIActor(targetActor);

            // Build report
            actor.callByActionName("addWorkflowInfo", "");
            actor.callByActionName("addJsonStateSection", "{\"actor\":\"node-localhost\"}");
            ActionResult result = actor.callByActionName("report", "");

            assertTrue(result.isSuccess());

            String report = result.getResult();

            // Verify order: WorkflowInfo (100) < JsonState (400)
            int workflowInfoIdx = report.indexOf("[Workflow Info]");
            int collectedDataIdx = report.indexOf("--- Collected Data");

            assertTrue(workflowInfoIdx < collectedDataIdx,
                "WorkflowInfo should come before Collected Data");

            // Verify content
            assertTrue(report.contains("main-cluster-status.yaml"));
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
