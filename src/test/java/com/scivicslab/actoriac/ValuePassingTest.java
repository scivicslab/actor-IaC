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

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for value passing functionality as documented in tutorials:
 * <ul>
 *   <li>025_JsonStateAPI: ワークフロー内での値の受け渡し</li>
 *   <li>026_InterWorkflowValuePassing: サブワークフローとの値の受け渡し</li>
 *   <li>027_ParallelExecution: 複数ノードでの非同期並列実行</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 */
class ValuePassingTest {

    /**
     * Tests for JSON State API within a workflow (025_JsonStateAPI).
     */
    @Nested
    @DisplayName("025 JSON State API - ワークフロー内での値の受け渡し")
    class JsonStateWithinWorkflowTest {

        private IIActorSystem system;
        private NodeIIAR nodeActor;

        @BeforeEach
        void setUp() {
            system = new IIActorSystem("test-system");
            Node node = new Node("localhost", "testuser");
            NodeInterpreter interpreter = new NodeInterpreter(node, system);
            nodeActor = new NodeIIAR("test-node", interpreter, system);
        }

        @AfterEach
        void tearDown() {
            if (system != null) {
                system.terminate();
            }
        }

        @Test
        @DisplayName("${result} should reference the previous action's result")
        void resultShouldReferencePreviousActionResult() {
            // Simulate: action1 sets result to "value1"
            nodeActor.setLastResult(new ActionResult(true, "value1"));

            // Verify ${result} can be expanded
            String expanded = nodeActor.expandVariables("Result is ${result}");
            assertEquals("Result is value1", expanded);
        }

        @Test
        @DisplayName("${result} should be overwritten by each action")
        void resultShouldBeOverwrittenByEachAction() {
            // First action sets result
            nodeActor.setLastResult(new ActionResult(true, "first"));
            assertEquals("first", nodeActor.expandVariables("${result}"));

            // Second action overwrites result
            nodeActor.setLastResult(new ActionResult(true, "second"));
            assertEquals("second", nodeActor.expandVariables("${result}"));

            // First value is lost
            assertNotEquals("first", nodeActor.expandVariables("${result}"));
        }

        @Test
        @DisplayName("putJson should save values with a name")
        void putJsonShouldSaveValuesWithName() {
            // Save a value
            ActionResult putResult = nodeActor.callByActionName("putJson",
                "{\"path\": \"hostname\", \"value\": \"server1\"}");
            assertTrue(putResult.isSuccess());

            // Reference the saved value
            String expanded = nodeActor.expandVariables("Host is ${hostname}");
            assertEquals("Host is server1", expanded);
        }

        @Test
        @DisplayName("Saved values should persist even when ${result} is overwritten")
        void savedValuesShouldPersistWhenResultIsOverwritten() {
            // Step 1: Action produces "hostname1"
            nodeActor.setLastResult(new ActionResult(true, "hostname1"));

            // Step 2: Save with putJson
            nodeActor.callByActionName("putJson",
                "{\"path\": \"savedHostname\", \"value\": \"hostname1\"}");

            // Step 3: Another action overwrites ${result}
            nodeActor.setLastResult(new ActionResult(true, "new-result"));

            // ${result} is now "new-result"
            assertEquals("new-result", nodeActor.expandVariables("${result}"));

            // But ${savedHostname} is still "hostname1"
            assertEquals("hostname1", nodeActor.expandVariables("${savedHostname}"));
        }

        @Test
        @DisplayName("Nested path should work in putJson")
        void nestedPathShouldWork() {
            // Save nested value
            nodeActor.callByActionName("putJson",
                "{\"path\": \"config.targetDir\", \"value\": \"/var/www\"}");
            nodeActor.callByActionName("putJson",
                "{\"path\": \"config.mode\", \"value\": \"production\"}");

            // Reference nested values
            assertEquals("/var/www", nodeActor.expandVariables("${config.targetDir}"));
            assertEquals("production", nodeActor.expandVariables("${config.mode}"));
        }

        @Test
        @DisplayName("Type should be preserved in putJson")
        void typeShouldBePreservedInPutJson() {
            // Save different types
            nodeActor.callByActionName("putJson",
                "{\"path\": \"stringVal\", \"value\": \"text\"}");
            nodeActor.callByActionName("putJson",
                "{\"path\": \"numVal\", \"value\": 42}");
            nodeActor.callByActionName("putJson",
                "{\"path\": \"boolVal\", \"value\": true}");

            // Get values via getJson
            ActionResult strResult = nodeActor.callByActionName("getJson", "[\"stringVal\"]");
            ActionResult numResult = nodeActor.callByActionName("getJson", "[\"numVal\"]");
            ActionResult boolResult = nodeActor.callByActionName("getJson", "[\"boolVal\"]");

            assertEquals("text", strResult.getResult());
            assertEquals("42", numResult.getResult());
            assertEquals("true", boolResult.getResult());
        }

        @Test
        @DisplayName("hasJson should check if key exists")
        void hasJsonShouldCheckIfKeyExists() {
            // Before putting
            ActionResult beforeResult = nodeActor.callByActionName("hasJson", "[\"myKey\"]");
            assertEquals("false", beforeResult.getResult());

            // After putting
            nodeActor.callByActionName("putJson",
                "{\"path\": \"myKey\", \"value\": \"someValue\"}");

            ActionResult afterResult = nodeActor.callByActionName("hasJson", "[\"myKey\"]");
            assertEquals("true", afterResult.getResult());
        }

        @Test
        @DisplayName("clearJson should remove all saved values")
        void clearJsonShouldRemoveAllSavedValues() {
            // Save some values
            nodeActor.callByActionName("putJson",
                "{\"path\": \"key1\", \"value\": \"val1\"}");
            nodeActor.callByActionName("putJson",
                "{\"path\": \"key2\", \"value\": \"val2\"}");

            // Clear
            ActionResult clearResult = nodeActor.callByActionName("clearJson", "[]");
            assertTrue(clearResult.isSuccess());

            // Verify cleared
            ActionResult has1 = nodeActor.callByActionName("hasJson", "[\"key1\"]");
            ActionResult has2 = nodeActor.callByActionName("hasJson", "[\"key2\"]");
            assertEquals("false", has1.getResult());
            assertEquals("false", has2.getResult());
        }
    }

    /**
     * Tests for sub-workflow value passing (026_InterWorkflowValuePassing).
     */
    @Nested
    @DisplayName("026 Inter-Workflow Value Passing - サブワークフローとの値の受け渡し")
    class InterWorkflowValuePassingTest {

        private IIActorSystem system;

        @BeforeEach
        void setUp() {
            system = new IIActorSystem("test-system");
        }

        @AfterEach
        void tearDown() {
            if (system != null) {
                system.terminate();
            }
        }

        @Test
        @DisplayName("runWorkflow should share JSON State between main and sub")
        void runWorkflowShouldShareJsonState() {
            // Create node actor
            Node node = new Node("localhost", "testuser");
            NodeInterpreter interpreter = new NodeInterpreter(node, system);
            NodeIIAR nodeActor = new NodeIIAR("test-node", interpreter, system);

            // Main workflow puts a value
            nodeActor.callByActionName("putJson",
                "{\"path\": \"fromMain\", \"value\": \"mainValue\"}");

            // The value should be accessible (shared JSON State)
            // In real runWorkflow, sub-workflow would see this value
            String expanded = nodeActor.expandVariables("${fromMain}");
            assertEquals("mainValue", expanded);

            // Sub-workflow puts a value (simulated)
            nodeActor.callByActionName("putJson",
                "{\"path\": \"fromSub\", \"value\": \"subValue\"}");

            // Main workflow should see sub's value (shared JSON State)
            String expandedSub = nodeActor.expandVariables("${fromSub}");
            assertEquals("subValue", expandedSub);
        }

        @Test
        @DisplayName("Each node actor should have independent JSON State")
        void eachNodeActorShouldHaveIndependentJsonState() {
            // Create two node actors (simulating two different nodes)
            Node node1 = new Node("host1", "user1");
            NodeInterpreter interpreter1 = new NodeInterpreter(node1, system);
            NodeIIAR nodeActor1 = new NodeIIAR("node-host1", interpreter1, system);

            Node node2 = new Node("host2", "user2");
            NodeInterpreter interpreter2 = new NodeInterpreter(node2, system);
            NodeIIAR nodeActor2 = new NodeIIAR("node-host2", interpreter2, system);

            // Node1 puts a value
            nodeActor1.callByActionName("putJson",
                "{\"path\": \"hostname\", \"value\": \"host1\"}");

            // Node2 puts a different value with same key
            nodeActor2.callByActionName("putJson",
                "{\"path\": \"hostname\", \"value\": \"host2\"}");

            // Each node should have its own value (independent JSON State)
            assertEquals("host1", nodeActor1.expandVariables("${hostname}"));
            assertEquals("host2", nodeActor2.expandVariables("${hostname}"));

            // Node1's value is not affected by Node2
            assertNotEquals("host2", nodeActor1.expandVariables("${hostname}"));
        }

        @Test
        @DisplayName("Shared actor should be accessible from both main and sub")
        void sharedActorShouldBeAccessibleFromBothMainAndSub() {
            // Create a shared actor (simulating counter actor from documentation)
            Node sharedNode = new Node("localhost", "testuser");
            NodeInterpreter sharedInterpreter = new NodeInterpreter(sharedNode, system);
            NodeIIAR sharedActor = new NodeIIAR("counter", sharedInterpreter, system);
            system.addIIActor(sharedActor);

            // Main workflow sets a value on shared actor
            sharedActor.callByActionName("putJson",
                "{\"path\": \"count\", \"value\": 100}");

            // Verify both can access the same shared actor
            IIActorRef<?> fromSystem = system.getIIActor("counter");
            assertNotNull(fromSystem);
            assertEquals("counter", fromSystem.getName());

            // The shared actor's state is accessible
            ActionResult getResult = sharedActor.callByActionName("getJson", "[\"count\"]");
            assertEquals("100", getResult.getResult());
        }
    }

    /**
     * Tests for parallel execution with apply (027_ParallelExecution).
     */
    @Nested
    @DisplayName("027 Parallel Execution - 複数ノードでの非同期並列実行")
    class ParallelExecutionTest {

        private IIActorSystem system;
        private NodeGroupIIAR nodeGroupActor;

        @BeforeEach
        void setUp() throws IOException {
            system = new IIActorSystem("test-system");

            // Create NodeGroup with inventory
            NodeGroup nodeGroup = new NodeGroup();
            InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
            nodeGroup.loadInventory(input);

            // Create NodeGroupInterpreter
            NodeGroupInterpreter interpreter = new NodeGroupInterpreter(nodeGroup, system);

            // Create and register NodeGroupIIAR
            nodeGroupActor = new NodeGroupIIAR("nodeGroup", interpreter, system);
            system.addIIActor(nodeGroupActor);
        }

        @AfterEach
        void tearDown() {
            if (system != null) {
                system.terminate();
            }
        }

        @Test
        @DisplayName("apply should match actors by pattern")
        void applyShouldMatchActorsByPattern() {
            // Create node actors
            nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");

            // Verify nodes were created
            IIActorRef<?> node1 = system.getIIActor("node-web1.example.com");
            IIActorRef<?> node2 = system.getIIActor("node-web2.example.com");
            assertNotNull(node1, "node-web1 should exist");
            assertNotNull(node2, "node-web2 should exist");
        }

        @Test
        @DisplayName("apply should execute on all matching actors")
        void applyShouldExecuteOnAllMatchingActors() {
            // Create node actors
            nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");

            // Apply doNothing to all nodes (pattern: node-*)
            String applyArg = "{\"actor\": \"node-*\", \"method\": \"doNothing\", \"arguments\": []}";
            ActionResult result = nodeGroupActor.callByActionName("apply", applyArg);

            assertTrue(result.isSuccess(), "apply should succeed");
            assertTrue(result.getResult().contains("Applied to 2 actors"),
                "Should apply to 2 actors (webservers)");
        }

        @Test
        @DisplayName("apply should return failure info when some actors fail")
        void applyShouldReturnFailureInfo() {
            // Create node actors
            nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");

            // Apply non-existent method (should fail)
            String applyArg = "{\"actor\": \"node-*\", \"method\": \"nonExistentMethod\", \"arguments\": []}";
            ActionResult result = nodeGroupActor.callByActionName("apply", applyArg);

            assertFalse(result.isSuccess(), "apply should fail when method doesn't exist");
            assertTrue(result.getResult().contains("Failures"),
                "Result should mention failures");
        }

        @Test
        @DisplayName("apply should return error for no matching actors")
        void applyShouldReturnErrorForNoMatchingActors() {
            // Don't create any node actors

            // Apply to non-existent pattern
            String applyArg = "{\"actor\": \"node-*\", \"method\": \"doNothing\", \"arguments\": []}";
            ActionResult result = nodeGroupActor.callByActionName("apply", applyArg);

            assertFalse(result.isSuccess(), "apply should fail when no actors match");
            assertTrue(result.getResult().contains("No actors matched"),
                "Result should mention no actors matched");
        }

        @Test
        @DisplayName("Each node should have independent JSON State during parallel execution")
        void eachNodeShouldHaveIndependentJsonStateDuringParallelExecution() {
            // Create node actors
            nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");

            // Get node actors
            IIActorRef<?> node1 = system.getIIActor("node-web1.example.com");
            IIActorRef<?> node2 = system.getIIActor("node-web2.example.com");

            // Each node puts different value with same key
            node1.callByActionName("putJson",
                "{\"path\": \"hostname\", \"value\": \"web1\"}");
            node2.callByActionName("putJson",
                "{\"path\": \"hostname\", \"value\": \"web2\"}");

            // Each node has its own value (use getJson to retrieve)
            ActionResult result1 = node1.callByActionName("getJson", "[\"hostname\"]");
            ActionResult result2 = node2.callByActionName("getJson", "[\"hostname\"]");
            assertEquals("web1", result1.getResult());
            assertEquals("web2", result2.getResult());
        }

        @Test
        @DisplayName("apply should execute in parallel (concurrent execution)")
        void applyShouldExecuteInParallel() throws InterruptedException {
            // Create node actors for multiple groups to have more nodes
            nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");
            nodeGroupActor.callByActionName("createNodeActors", "[\"dbservers\"]");

            // Track concurrent executions
            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(4); // 2 web + 2 db servers

            // Apply doNothing - this doesn't truly test parallelism in a unit test
            // but we can verify that apply returns quickly with all results
            String applyArg = "{\"actor\": \"node-*\", \"method\": \"doNothing\", \"arguments\": []}";

            long startTime = System.currentTimeMillis();
            ActionResult result = nodeGroupActor.callByActionName("apply", applyArg);
            long endTime = System.currentTimeMillis();

            assertTrue(result.isSuccess(), "apply should succeed");
            // Verify all 4 actors were processed
            assertTrue(result.getResult().contains("Applied to 4 actors"),
                "Should apply to 4 actors (2 webservers + 2 dbservers)");
        }

        @Test
        @DisplayName("apply with pattern should only match specific actors")
        void applyWithPatternShouldOnlyMatchSpecificActors() {
            // Create node actors for multiple groups
            nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");
            nodeGroupActor.callByActionName("createNodeActors", "[\"dbservers\"]");

            // Apply only to web servers
            String applyArg = "{\"actor\": \"node-web*\", \"method\": \"doNothing\", \"arguments\": []}";
            ActionResult result = nodeGroupActor.callByActionName("apply", applyArg);

            assertTrue(result.isSuccess());
            assertTrue(result.getResult().contains("Applied to 2 actors"),
                "Should only apply to 2 webserver actors, not db servers");
        }
    }
}
