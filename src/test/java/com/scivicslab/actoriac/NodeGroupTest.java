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

import com.scivicslab.pojoactor.ActorRef;
import com.scivicslab.pojoactor.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NodeGroup.
 *
 * <p>NodeGroup is a pure POJO that creates Node objects. These tests verify
 * the POJO behavior without requiring ActorSystem.</p>
 *
 * @author devteam@scivics-lab.com
 */
@DisplayName("NodeGroup Tests")
class NodeGroupTest {

    private NodeGroup nodeGroup;

    @BeforeEach
    void setUp() {
        nodeGroup = new NodeGroup();
    }

    @Test
    @DisplayName("Should load inventory file")
    void testLoadInventory() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        nodeGroup.loadInventory(input);

        assertNotNull(nodeGroup.getInventory(), "Inventory should be loaded");
    }

    @Test
    @DisplayName("Should create nodes for webservers group")
    void testCreateNodesForWebservers() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        List<Node> webservers = nodeGroup.createNodesForGroup("webservers");

        assertEquals(2, webservers.size(), "Should create 2 webserver nodes");
    }

    @Test
    @DisplayName("Should create nodes for dbservers group")
    void testCreateNodesForDbservers() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        List<Node> dbservers = nodeGroup.createNodesForGroup("dbservers");

        assertEquals(2, dbservers.size(), "Should create 2 dbserver nodes");
    }

    @Test
    @DisplayName("Should create nodes for multiple groups")
    void testCreateNodesForMultipleGroups() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        List<Node> webservers = nodeGroup.createNodesForGroup("webservers");
        List<Node> dbservers = nodeGroup.createNodesForGroup("dbservers");
        List<Node> loadbalancers = nodeGroup.createNodesForGroup("loadbalancers");

        assertEquals(2, webservers.size(), "Should create 2 webserver nodes");
        assertEquals(2, dbservers.size(), "Should create 2 dbserver nodes");
        assertEquals(1, loadbalancers.size(), "Should create 1 loadbalancer node");
    }

    @Test
    @DisplayName("Should throw exception when creating nodes without loading inventory")
    void testCreateNodesWithoutInventory() {
        assertThrows(IllegalStateException.class, () -> {
            nodeGroup.createNodesForGroup("webservers");
        }, "Should throw IllegalStateException when inventory not loaded");
    }

    @Test
    @DisplayName("Should apply global variables to nodes without host-specific vars")
    void testGlobalVariablesApplied() throws Exception {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        List<Node> loadbalancers = nodeGroup.createNodesForGroup("loadbalancers");

        // lb1 has no host-specific vars, should use global vars
        Node lb1 = loadbalancers.get(0);
        assertEquals("testuser", lb1.getUser(), "lb1 should use user from global vars");
        assertEquals(22, lb1.getPort(), "lb1 should use port from global vars");
    }

    @Test
    @DisplayName("Should return nodeGroup information")
    void testClusterToString() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        String clusterInfo = nodeGroup.toString();
        assertTrue(clusterInfo.contains("vaultEnabled=false"), "Should show Vault not enabled");
        assertTrue(clusterInfo.contains("webservers"), "Should show groups");
    }

    @Test
    @DisplayName("Should apply host-specific variables to nodes")
    void testHostSpecificVariablesApplied() throws Exception {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        List<Node> webservers = nodeGroup.createNodesForGroup("webservers");

        // Find web1 and web2 by hostname
        Node web1 = webservers.stream()
            .filter(n -> n.getHostname().equals("web1.example.com"))
            .findFirst()
            .orElseThrow();
        Node web2 = webservers.stream()
            .filter(n -> n.getHostname().equals("web2.example.com"))
            .findFirst()
            .orElseThrow();

        // web1 should use host-specific user "webadmin"
        assertEquals("webadmin", web1.getUser(), "web1 should use host-specific user");

        // web2 should use host-specific port 2222, but global user
        assertEquals(2222, web2.getPort(), "web2 should use host-specific port");
        assertEquals("testuser", web2.getUser(), "web2 should use global user");
    }

    @Test
    @DisplayName("Should apply variables with correct priority")
    void testVariablePriority() throws Exception {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        nodeGroup.loadInventory(input);

        List<Node> dbservers = nodeGroup.createNodesForGroup("dbservers");

        // Find db1 and db2 by hostname
        Node db1 = dbservers.stream()
            .filter(n -> n.getHostname().equals("db1.example.com"))
            .findFirst()
            .orElseThrow();
        Node db2 = dbservers.stream()
            .filter(n -> n.getHostname().equals("db2.example.com"))
            .findFirst()
            .orElseThrow();

        // db1 has host-specific user and port
        assertEquals("postgres", db1.getUser(), "db1 should use host-specific user");
        assertEquals(5432, db1.getPort(), "db1 should use host-specific port");

        // db2 has only global vars
        assertEquals("testuser", db2.getUser(), "db2 should use global user");
        assertEquals(22, db2.getPort(), "db2 should use global port");
    }

    @Test
    @DisplayName("Should create nodeGroup using Builder pattern")
    void testBuilderPattern() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");

        NodeGroup nodeGroup = new NodeGroup.Builder()
            .withInventory(input)
            .build();

        assertNotNull(nodeGroup.getInventory(), "Inventory should be loaded via Builder");

        List<Node> webservers = nodeGroup.createNodesForGroup("webservers");
        assertEquals(2, webservers.size(), "Should create 2 webserver nodes via Builder");
    }

    @Test
    @DisplayName("Should create nodeGroup with Vault using Builder pattern")
    void testBuilderPatternWithVault() throws IOException {
        // Note: This test uses null VaultClient as we don't have a real Vault server
        // In production, you would pass a real VaultClient instance
        InputStream inventoryInput = getClass().getResourceAsStream("/test-inventory.ini");

        NodeGroup nodeGroup = new NodeGroup.Builder()
            .withInventory(inventoryInput)
            .build();

        assertNotNull(nodeGroup.getInventory(), "Inventory should be loaded");
        assertNull(nodeGroup.getVaultClient(), "VaultClient should be null when not configured");
    }

    @Test
    @DisplayName("Builder should support method chaining")
    void testBuilderMethodChaining() throws IOException {
        InputStream inventoryInput = getClass().getResourceAsStream("/test-inventory.ini");

        // Method chaining should work
        NodeGroup.Builder builder = new NodeGroup.Builder();
        NodeGroup.Builder sameBuilder = builder.withInventory(inventoryInput);

        assertSame(builder, sameBuilder, "withInventory() should return same builder instance");

        NodeGroup nodeGroup = sameBuilder.build();
        assertNotNull(nodeGroup, "Builder should create nodeGroup");
        assertNotNull(nodeGroup.getInventory(), "Inventory should be loaded");
    }
}
