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

import com.scivicslab.actoriac.accumulator.ConsoleAccumulator;
import com.scivicslab.actoriac.accumulator.MultiplexerAccumulator;
import com.scivicslab.actoriac.accumulator.MultiplexerAccumulatorIIAR;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MultiplexerAccumulator functionality.
 *
 * <p>Verifies that the MultiplexerAccumulatorIIAR works correctly with the
 * new loose-coupling architecture.</p>
 *
 * <p>According to the updated design (120_AccumulatorOutputIssue_260117_oo01),
 * the actor tree should look like:</p>
 * <pre>
 * ROOTアクター
 * ├── nodeGroupアクター
 * │   └── node-*アクター
 * └── outputMultiplexerアクター  &lt;-- Created by RunCLI, not nodeGroup
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
@DisplayName("Accumulator Auto Creation Tests")
class AccumulatorAutoCreationTest {

    private IIActorSystem system;
    private NodeGroupInterpreter nodeGroupInterpreter;
    private NodeGroupIIAR nodeGroupActor;

    @BeforeEach
    void setUp() throws IOException {
        // Create actor system
        system = new IIActorSystem("test-system");

        // Create NodeGroup with inventory
        NodeGroup nodeGroup = new NodeGroup();
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        // Create NodeGroupInterpreter
        nodeGroupInterpreter = new NodeGroupInterpreter(nodeGroup, system);

        // Create and register NodeGroupIIAR
        nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroupInterpreter, system);
        system.addIIActor(nodeGroupActor);
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    @Test
    @DisplayName("Accumulator should not exist before outputMultiplexer is registered")
    void accumulatorShouldNotExistBeforeNodeCreation() {
        // Before registering outputMultiplexer, hasAccumulator should return false
        ActionResult result = nodeGroupActor.callByActionName("hasAccumulator", "[]");

        assertFalse(result.isSuccess(), "hasAccumulator should return false before outputMultiplexer is registered");
    }

    @Test
    @DisplayName("createAccumulator should return success (no-op for backward compatibility)")
    void createAccumulatorShouldWork() {
        // createAccumulator is now a no-op, but should still succeed for backward compatibility
        ActionResult createResult = nodeGroupActor.callByActionName("createAccumulator", "[\"streaming\"]");
        assertTrue(createResult.isSuccess(), "createAccumulator should succeed (no-op)");

        // Accumulator won't exist because it's now created by RunCLI, not nodeGroup
        // This is expected behavior - the test verifies backward compatibility
    }

    @Test
    @DisplayName("MultiplexerAccumulator should be accessible after manual registration")
    void accumulatorShouldBeAccessibleAfterNodeCreation() {
        // Step 1: Create node actors
        ActionResult createNodesResult = nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");
        assertTrue(createNodesResult.isSuccess(), "createNodeActors should succeed");

        // Step 2: Manually register MultiplexerAccumulator (simulating RunCLI behavior)
        MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
        ConsoleAccumulator console = new ConsoleAccumulator();
        console.setQuiet(true); // quiet mode for tests
        multiplexer.addTarget(console);
        MultiplexerAccumulatorIIAR multiplexerActor = new MultiplexerAccumulatorIIAR(
            "outputMultiplexer", multiplexer, system);
        system.addIIActor(multiplexerActor);

        // Verify accumulator is accessible via system.getIIActor()
        IIActorRef<?> accumulator = system.getIIActor("outputMultiplexer");
        assertNotNull(accumulator, "Accumulator should be accessible via system.getIIActor()");
        assertEquals("outputMultiplexer", accumulator.getName(), "Accumulator name should be 'outputMultiplexer'");

        // Verify hasAccumulator now returns true
        ActionResult hasResult = nodeGroupActor.callByActionName("hasAccumulator", "[]");
        assertTrue(hasResult.isSuccess(), "hasAccumulator should return true after registration");
    }

    @Test
    @DisplayName("MultiplexerAccumulator should support 'add' action")
    void accumulatorShouldSupportAddAction() {
        // Register MultiplexerAccumulator
        MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
        ConsoleAccumulator console = new ConsoleAccumulator();
        console.setQuiet(true); // quiet mode
        multiplexer.addTarget(console);
        MultiplexerAccumulatorIIAR multiplexerActor = new MultiplexerAccumulatorIIAR(
            "outputMultiplexer", multiplexer, system);
        system.addIIActor(multiplexerActor);

        // Get accumulator actor
        IIActorRef<?> accumulator = system.getIIActor("outputMultiplexer");
        assertNotNull(accumulator, "Accumulator should exist");

        // Call 'add' action
        String addArg = "{\"source\":\"test-node\",\"type\":\"test\",\"data\":\"test data\"}";
        ActionResult addResult = accumulator.callByActionName("add", addArg);
        assertTrue(addResult.isSuccess(), "add action should succeed");
    }

    @Test
    @DisplayName("MultiplexerAccumulator should support 'getSummary' action")
    void accumulatorShouldSupportGetSummaryAction() {
        // Register MultiplexerAccumulator
        MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
        ConsoleAccumulator console = new ConsoleAccumulator();
        console.setQuiet(true); // quiet mode
        multiplexer.addTarget(console);
        MultiplexerAccumulatorIIAR multiplexerActor = new MultiplexerAccumulatorIIAR(
            "outputMultiplexer", multiplexer, system);
        system.addIIActor(multiplexerActor);

        // Add some data
        IIActorRef<?> accumulator = system.getIIActor("outputMultiplexer");
        accumulator.callByActionName("add", "{\"source\":\"node1\",\"type\":\"cpu\",\"data\":\"Intel i7\"}");

        // Get summary via nodeGroupActor
        ActionResult summaryResult = nodeGroupActor.callByActionName("getAccumulatorSummary", "[]");
        assertTrue(summaryResult.isSuccess(), "getAccumulatorSummary should succeed");
    }

    @Test
    @DisplayName("Different accumulator types should be supported (backward compatibility)")
    void differentAccumulatorTypesShouldBeSupported() {
        // Test streaming type - should succeed as no-op
        ActionResult streamingResult = nodeGroupActor.callByActionName("createAccumulator", "[\"streaming\"]");
        assertTrue(streamingResult.isSuccess(), "streaming accumulator should succeed (no-op)");

        // Reset for next test - need new system
        system.terminate();
        system = new IIActorSystem("test-system");
        nodeGroupInterpreter = new NodeGroupInterpreter(new NodeGroup(), system);
        nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroupInterpreter, system);
        system.addIIActor(nodeGroupActor);

        // Test buffered type - should succeed as no-op
        ActionResult bufferedResult = nodeGroupActor.callByActionName("createAccumulator", "[\"buffered\"]");
        assertTrue(bufferedResult.isSuccess(), "buffered accumulator should succeed (no-op)");
    }

    @Test
    @DisplayName("MultiplexerAccumulator should be a sibling of nodeGroup (not a child)")
    void accumulatorShouldBeSiblingOfNodeGroup() {
        // Register MultiplexerAccumulator
        MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
        MultiplexerAccumulatorIIAR multiplexerActor = new MultiplexerAccumulatorIIAR(
            "outputMultiplexer", multiplexer, system);
        system.addIIActor(multiplexerActor);

        // Verify accumulator is NOT a child of nodeGroup (it's a sibling, under ROOT)
        assertFalse(nodeGroupActor.getNamesOfChildren().contains("outputMultiplexer"),
                "outputMultiplexer should NOT be a child of nodeGroup");

        // Verify it exists at the system level
        assertNotNull(system.getIIActor("outputMultiplexer"),
                "outputMultiplexer should be accessible at the system level");
    }

    @Test
    @DisplayName("Node actors should be able to find outputMultiplexer via system")
    void nodeActorsShouldBeAbleToFindAccumulator() {
        // Create node actors first
        ActionResult createNodesResult = nodeGroupActor.callByActionName("createNodeActors", "[\"webservers\"]");
        assertTrue(createNodesResult.isSuccess(), "createNodeActors should succeed");

        // Register MultiplexerAccumulator
        MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
        MultiplexerAccumulatorIIAR multiplexerActor = new MultiplexerAccumulatorIIAR(
            "outputMultiplexer", multiplexer, system);
        system.addIIActor(multiplexerActor);

        // Get a node actor
        IIActorRef<?> nodeActor = system.getIIActor("node-web1.example.com");
        assertNotNull(nodeActor, "node actor should exist");

        // Verify node can find outputMultiplexer via system (same system instance)
        IIActorRef<?> accumulator = system.getIIActor("outputMultiplexer");
        assertNotNull(accumulator, "Node should be able to find outputMultiplexer via system");
    }
}
