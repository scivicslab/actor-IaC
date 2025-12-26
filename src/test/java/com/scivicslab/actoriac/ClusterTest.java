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
 * Tests for Cluster.
 *
 * @author devteam@scivics-lab.com
 */
@DisplayName("Cluster Tests")
class ClusterTest {

    private ActorSystem actorSystem;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem("test-cluster-system", 2);
        cluster = new Cluster(actorSystem);
    }

    @AfterEach
    void tearDown() {
        actorSystem.terminate();
    }

    @Test
    @DisplayName("Should load inventory file")
    void testLoadInventory() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        cluster.loadInventory(input);

        assertNotNull(cluster.getInventory(), "Inventory should be loaded");
    }

    @Test
    @DisplayName("Should create node actors for webservers group")
    void testCreateNodesForWebservers() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        List<ActorRef<Node>> webservers = cluster.createNodesForGroup("webservers");

        assertEquals(2, webservers.size(), "Should create 2 webserver node actors");
        assertEquals(2, cluster.getNodeCount(), "Cluster should have 2 nodes");
    }

    @Test
    @DisplayName("Should create node actors for dbservers group")
    void testCreateNodesForDbservers() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        List<ActorRef<Node>> dbservers = cluster.createNodesForGroup("dbservers");

        assertEquals(2, dbservers.size(), "Should create 2 dbserver node actors");
    }

    @Test
    @DisplayName("Should create node actors for multiple groups")
    void testCreateNodesForMultipleGroups() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        List<ActorRef<Node>> webservers = cluster.createNodesForGroup("webservers");
        List<ActorRef<Node>> dbservers = cluster.createNodesForGroup("dbservers");
        List<ActorRef<Node>> loadbalancers = cluster.createNodesForGroup("loadbalancers");

        assertEquals(2, webservers.size(), "Should create 2 webserver actors");
        assertEquals(2, dbservers.size(), "Should create 2 dbserver actors");
        assertEquals(1, loadbalancers.size(), "Should create 1 loadbalancer actor");
        assertEquals(5, cluster.getNodeCount(), "Cluster should have 5 nodes total");
    }

    @Test
    @DisplayName("Should get node actor by hostname")
    void testGetNodeActor() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        cluster.createNodesForGroup("webservers");

        ActorRef<Node> web1 = cluster.getNodeActor("web1.example.com");
        assertNotNull(web1, "Should find web1 node actor");

        ActorRef<Node> web2 = cluster.getNodeActor("web2.example.com");
        assertNotNull(web2, "Should find web2 node actor");

        ActorRef<Node> nonexistent = cluster.getNodeActor("nonexistent.example.com");
        assertNull(nonexistent, "Should return null for nonexistent node");
    }

    @Test
    @DisplayName("Should throw exception when creating nodes without loading inventory")
    void testCreateNodesWithoutInventory() {
        assertThrows(IllegalStateException.class, () -> {
            cluster.createNodesForGroup("webservers");
        }, "Should throw IllegalStateException when inventory not loaded");
    }

    @Test
    @DisplayName("Should apply global variables to nodes without host-specific vars")
    void testGlobalVariablesApplied() throws Exception {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        List<ActorRef<Node>> loadbalancers = cluster.createNodesForGroup("loadbalancers");

        // lb1 has no host-specific vars, should use global vars
        ActorRef<Node> lb1Actor = loadbalancers.get(0);
        String user = lb1Actor.ask(node -> node.getUser()).get();
        int port = lb1Actor.ask(node -> node.getPort()).get();

        assertEquals("testuser", user, "lb1 should use user from global vars");
        assertEquals(22, port, "lb1 should use port from global vars");
    }

    @Test
    @DisplayName("Should return cluster information")
    void testClusterToString() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);
        cluster.createNodesForGroup("webservers");

        String clusterInfo = cluster.toString();
        assertTrue(clusterInfo.contains("nodeCount=2"), "Should show node count");
    }

    @Test
    @DisplayName("Should apply host-specific variables to nodes")
    void testHostSpecificVariablesApplied() throws Exception {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        List<ActorRef<Node>> webservers = cluster.createNodesForGroup("webservers");

        // web1 should use host-specific user "webadmin"
        ActorRef<Node> web1Actor = cluster.getNodeActor("web1.example.com");
        String web1User = web1Actor.ask(node -> node.getUser()).get();
        assertEquals("webadmin", web1User, "web1 should use host-specific user");

        // web2 should use host-specific port 2222, but global user
        ActorRef<Node> web2Actor = cluster.getNodeActor("web2.example.com");
        int web2Port = web2Actor.ask(node -> node.getPort()).get();
        String web2User = web2Actor.ask(node -> node.getUser()).get();
        assertEquals(2222, web2Port, "web2 should use host-specific port");
        assertEquals("testuser", web2User, "web2 should use global user");
    }

    @Test
    @DisplayName("Should apply variables with correct priority")
    void testVariablePriority() throws Exception {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        cluster.loadInventory(input);

        List<ActorRef<Node>> dbservers = cluster.createNodesForGroup("dbservers");

        // db1 has host-specific user and port
        ActorRef<Node> db1Actor = cluster.getNodeActor("db1.example.com");
        String db1User = db1Actor.ask(node -> node.getUser()).get();
        int db1Port = db1Actor.ask(node -> node.getPort()).get();
        assertEquals("postgres", db1User, "db1 should use host-specific user");
        assertEquals(5432, db1Port, "db1 should use host-specific port");

        // db2 has only global vars
        ActorRef<Node> db2Actor = cluster.getNodeActor("db2.example.com");
        String db2User = db2Actor.ask(node -> node.getUser()).get();
        int db2Port = db2Actor.ask(node -> node.getPort()).get();
        assertEquals("testuser", db2User, "db2 should use global user");
        assertEquals(22, db2Port, "db2 should use global port");
    }
}
