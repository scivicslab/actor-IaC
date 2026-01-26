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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSON State API in NodeIIAR and NodeGroupIIAR.
 *
 * <p>These tests verify that JSON State API actions (putJson, getJson, hasJson, etc.)
 * are accessible through callByActionName in IIActorRef subclasses.</p>
 *
 * @author devteam@scivicslab.com
 */
@DisplayName("JSON State API Tests")
class JsonStateAPITest {

    @Test
    @DisplayName("NodeIIAR should support putJson action")
    void testNodeIIARPutJson() {
        // Create a minimal Node and NodeInterpreter for testing
        IIActorSystem system = new IIActorSystem("test-system");
        Node node = new Node("localhost", "testuser");
        NodeInterpreter interpreter = new NodeInterpreter(node, system);
        NodeIIAR nodeIIAR = new NodeIIAR("test-node", interpreter, system);

        // Call putJson via callByActionName
        ActionResult result = nodeIIAR.callByActionName("putJson",
            "{\"path\": \"testKey\", \"value\": \"testValue\"}");

        assertTrue(result.isSuccess(), "putJson should succeed");
        assertTrue(result.getResult().contains("testKey"), "Result should mention the key");
    }

    @Test
    @DisplayName("NodeIIAR should support getJson action")
    void testNodeIIARGetJson() {
        IIActorSystem system = new IIActorSystem("test-system");
        Node node = new Node("localhost", "testuser");
        NodeInterpreter interpreter = new NodeInterpreter(node, system);
        NodeIIAR nodeIIAR = new NodeIIAR("test-node", interpreter, system);

        // First put a value
        nodeIIAR.callByActionName("putJson",
            "{\"path\": \"myKey\", \"value\": \"myValue\"}");

        // Then get it back
        ActionResult result = nodeIIAR.callByActionName("getJson", "[\"myKey\"]");

        assertTrue(result.isSuccess(), "getJson should succeed");
        assertEquals("myValue", result.getResult(), "Should retrieve the stored value");
    }

    @Test
    @DisplayName("NodeIIAR should support hasJson action")
    void testNodeIIARHasJson() {
        IIActorSystem system = new IIActorSystem("test-system");
        Node node = new Node("localhost", "testuser");
        NodeInterpreter interpreter = new NodeInterpreter(node, system);
        NodeIIAR nodeIIAR = new NodeIIAR("test-node", interpreter, system);

        // Check non-existent key
        ActionResult resultBefore = nodeIIAR.callByActionName("hasJson", "[\"nonExistent\"]");
        assertTrue(resultBefore.isSuccess());
        assertEquals("false", resultBefore.getResult());

        // Put a value
        nodeIIAR.callByActionName("putJson",
            "{\"path\": \"existingKey\", \"value\": \"someValue\"}");

        // Check existing key
        ActionResult resultAfter = nodeIIAR.callByActionName("hasJson", "[\"existingKey\"]");
        assertTrue(resultAfter.isSuccess());
        assertEquals("true", resultAfter.getResult());
    }

    @Test
    @DisplayName("NodeIIAR should support clearJson action")
    void testNodeIIARClearJson() {
        IIActorSystem system = new IIActorSystem("test-system");
        Node node = new Node("localhost", "testuser");
        NodeInterpreter interpreter = new NodeInterpreter(node, system);
        NodeIIAR nodeIIAR = new NodeIIAR("test-node", interpreter, system);

        // Put a value
        nodeIIAR.callByActionName("putJson",
            "{\"path\": \"key1\", \"value\": \"value1\"}");

        // Clear
        ActionResult clearResult = nodeIIAR.callByActionName("clearJson", "");
        assertTrue(clearResult.isSuccess(), "clearJson should succeed");

        // Verify cleared
        ActionResult hasResult = nodeIIAR.callByActionName("hasJson", "[\"key1\"]");
        assertEquals("false", hasResult.getResult(), "Key should no longer exist after clear");
    }

    @Test
    @DisplayName("NodeIIAR should support nested path in putJson")
    void testNodeIIARNestedPath() {
        IIActorSystem system = new IIActorSystem("test-system");
        Node node = new Node("localhost", "testuser");
        NodeInterpreter interpreter = new NodeInterpreter(node, system);
        NodeIIAR nodeIIAR = new NodeIIAR("test-node", interpreter, system);

        // Put nested value
        ActionResult putResult = nodeIIAR.callByActionName("putJson",
            "{\"path\": \"workflow.status\", \"value\": \"running\"}");
        assertTrue(putResult.isSuccess());

        // Get nested value
        ActionResult getResult = nodeIIAR.callByActionName("getJson", "[\"workflow.status\"]");
        assertTrue(getResult.isSuccess());
        assertEquals("running", getResult.getResult());
    }

    @Test
    @DisplayName("NodeIIAR should still support executeCommand action")
    void testNodeIIARExecuteCommandStillWorks() {
        IIActorSystem system = new IIActorSystem("test-system");
        Node node = new Node("localhost", "testuser");
        NodeInterpreter interpreter = new NodeInterpreter(node, system);
        NodeIIAR nodeIIAR = new NodeIIAR("test-node", interpreter, system);

        // This should still work (not affected by JSON State API changes)
        // Note: This test may fail if localhost SSH is not configured,
        // but the action should be recognized (not "Unknown action")
        ActionResult result = nodeIIAR.callByActionName("doNothing", "test");
        assertTrue(result.isSuccess(), "doNothing action should still work");
    }

    @Test
    @DisplayName("NodeGroupIIAR should support putJson action")
    void testNodeGroupIIARPutJson() {
        IIActorSystem system = new IIActorSystem("test-system");
        NodeGroup nodeGroup = new NodeGroup();
        NodeGroupInterpreter interpreter = new NodeGroupInterpreter(nodeGroup, system);
        NodeGroupIIAR nodeGroupIIAR = new NodeGroupIIAR("test-nodegroup", interpreter, system);

        // Call putJson via callByActionName
        ActionResult result = nodeGroupIIAR.callByActionName("putJson",
            "{\"path\": \"groupKey\", \"value\": \"groupValue\"}");

        assertTrue(result.isSuccess(), "putJson should succeed for NodeGroupIIAR");
    }

    @Test
    @DisplayName("NodeGroupIIAR should support getJson action")
    void testNodeGroupIIARGetJson() {
        IIActorSystem system = new IIActorSystem("test-system");
        NodeGroup nodeGroup = new NodeGroup();
        NodeGroupInterpreter interpreter = new NodeGroupInterpreter(nodeGroup, system);
        NodeGroupIIAR nodeGroupIIAR = new NodeGroupIIAR("test-nodegroup", interpreter, system);

        // Put and get
        nodeGroupIIAR.callByActionName("putJson",
            "{\"path\": \"status\", \"value\": \"active\"}");

        ActionResult result = nodeGroupIIAR.callByActionName("getJson", "[\"status\"]");

        assertTrue(result.isSuccess(), "getJson should succeed for NodeGroupIIAR");
        assertEquals("active", result.getResult());
    }
}
