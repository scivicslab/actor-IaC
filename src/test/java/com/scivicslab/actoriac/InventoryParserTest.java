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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InventoryParser.
 *
 * @author devteam@scivics-lab.com
 */
@DisplayName("Inventory Parser Tests")
class InventoryParserTest {

    @Test
    @DisplayName("Should parse inventory file with groups")
    void testParseInventoryGroups() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        InventoryParser.Inventory inventory = InventoryParser.parse(input);

        // Check groups
        Map<String, List<String>> groups = inventory.getAllGroups();
        assertTrue(groups.containsKey("webservers"), "Should have webservers group");
        assertTrue(groups.containsKey("dbservers"), "Should have dbservers group");
        assertTrue(groups.containsKey("loadbalancers"), "Should have loadbalancers group");

        // Check webservers hosts
        List<String> webservers = inventory.getHosts("webservers");
        assertEquals(2, webservers.size(), "Webservers should have 2 hosts");
        assertTrue(webservers.contains("web1.example.com"), "Should contain web1");
        assertTrue(webservers.contains("web2.example.com"), "Should contain web2");

        // Check dbservers hosts
        List<String> dbservers = inventory.getHosts("dbservers");
        assertEquals(2, dbservers.size(), "Dbservers should have 2 hosts");
        assertTrue(dbservers.contains("db1.example.com"), "Should contain db1");
        assertTrue(dbservers.contains("db2.example.com"), "Should contain db2");

        // Check loadbalancers hosts
        List<String> loadbalancers = inventory.getHosts("loadbalancers");
        assertEquals(1, loadbalancers.size(), "Loadbalancers should have 1 host");
        assertTrue(loadbalancers.contains("lb1.example.com"), "Should contain lb1");
    }

    @Test
    @DisplayName("Should parse global variables")
    void testParseGlobalVars() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        InventoryParser.Inventory inventory = InventoryParser.parse(input);

        Map<String, String> globalVars = inventory.getGlobalVars();
        assertEquals("testuser", globalVars.get("ansible_user"), "Should have ansible_user");
        assertEquals("22", globalVars.get("ansible_port"), "Should have ansible_port");
    }

    @Test
    @DisplayName("Should parse group-specific variables")
    void testParseGroupVars() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        InventoryParser.Inventory inventory = InventoryParser.parse(input);

        Map<String, String> webserversVars = inventory.getGroupVars("webservers");
        assertEquals("8080", webserversVars.get("http_port"), "Webservers should have http_port");
    }

    @Test
    @DisplayName("Should handle empty group")
    void testEmptyGroup() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        InventoryParser.Inventory inventory = InventoryParser.parse(input);

        List<String> nonexistent = inventory.getHosts("nonexistent");
        assertTrue(nonexistent.isEmpty(), "Nonexistent group should return empty list");
    }

    @Test
    @DisplayName("Should parse host-specific variables")
    void testParseHostVars() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-inventory.ini");
        assertNotNull(input, "Test inventory file should exist");

        InventoryParser.Inventory inventory = InventoryParser.parse(input);

        // Check web1 has custom user
        Map<String, String> web1Vars = inventory.getHostVars("web1.example.com");
        assertEquals("webadmin", web1Vars.get("ansible_user"),
            "web1 should have custom user");

        // Check web2 has custom port
        Map<String, String> web2Vars = inventory.getHostVars("web2.example.com");
        assertEquals("2222", web2Vars.get("ansible_port"),
            "web2 should have custom port");

        // Check db1 has both custom user and port
        Map<String, String> db1Vars = inventory.getHostVars("db1.example.com");
        assertEquals("postgres", db1Vars.get("ansible_user"),
            "db1 should have custom user");
        assertEquals("5432", db1Vars.get("ansible_port"),
            "db1 should have custom port");

        // Check db2 has no host-specific vars
        Map<String, String> db2Vars = inventory.getHostVars("db2.example.com");
        assertTrue(db2Vars.isEmpty(), "db2 should have no host-specific vars");
    }
}
