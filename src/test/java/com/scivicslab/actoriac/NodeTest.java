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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Node.
 *
 * @author devteam@scivicslab.com
 */
@DisplayName("Node Tests")
class NodeTest {

    @Test
    @DisplayName("Should create node with full parameters")
    void testCreateNodeWithFullParameters() {
        Node node = new Node("example.com", "testuser", 2222);

        assertEquals("example.com", node.getHostname());
        assertEquals("testuser", node.getUser());
        assertEquals(2222, node.getPort());
    }

    @Test
    @DisplayName("Should create node with default port")
    void testCreateNodeWithDefaults() {
        Node node = new Node("example.com", "testuser");

        assertEquals("example.com", node.getHostname());
        assertEquals("testuser", node.getUser());
        assertEquals(22, node.getPort());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void testToString() {
        Node node = new Node("example.com", "testuser", 22);

        String str = node.toString();
        assertTrue(str.contains("example.com"));
        assertTrue(str.contains("testuser"));
        assertTrue(str.contains("22"));
    }

    @Test
    @DisplayName("CommandResult should indicate success for exit code 0")
    void testCommandResultSuccess() {
        Node.CommandResult result = new Node.CommandResult("output", "", 0);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertEquals("output", result.getStdout());
        assertEquals("", result.getStderr());
    }

    @Test
    @DisplayName("CommandResult should indicate failure for non-zero exit code")
    void testCommandResultFailure() {
        Node.CommandResult result = new Node.CommandResult("", "error message", 1);

        assertFalse(result.isSuccess());
        assertEquals(1, result.getExitCode());
        assertEquals("", result.getStdout());
        assertEquals("error message", result.getStderr());
    }

    @Test
    @DisplayName("CommandResult should have meaningful toString")
    void testCommandResultToString() {
        Node.CommandResult result = new Node.CommandResult("output", "error", 2);

        String str = result.toString();
        assertTrue(str.contains("2"));
        assertTrue(str.contains("output"));
        assertTrue(str.contains("error"));
    }
}
