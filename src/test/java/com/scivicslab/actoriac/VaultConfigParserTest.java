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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VaultConfigParser.
 *
 * @author devteam@scivics-lab.com
 */
@DisplayName("VaultConfigParser Tests")
class VaultConfigParserTest {

    @Test
    @DisplayName("Should parse vault-config.ini file")
    void testParseVaultConfig() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        assertNotNull(input, "Test vault-config file should exist");

        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);
        assertNotNull(vaultPaths, "VaultPaths should not be null");
    }

    @Test
    @DisplayName("Should parse global Vault paths")
    void testParseGlobalPaths() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);

        Map<String, String> globalPaths = vaultPaths.getGlobalPaths();
        assertEquals("secret/data/ssh/iacuser/private_key", globalPaths.get("ssh_key_path"),
            "Should have global SSH key path");
        assertEquals("secret/data/sudo/iacuser/password", globalPaths.get("sudo_password_path"),
            "Should have global sudo password path");
    }

    @Test
    @DisplayName("Should parse group-specific Vault paths")
    void testParseGroupPaths() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);

        Map<String, String> webserversPaths = vaultPaths.getGroupPaths("webservers");
        assertEquals("secret/data/ssh/webadmin/private_key", webserversPaths.get("ssh_key_path"),
            "Should have webservers SSH key path");
        assertEquals("secret/data/sudo/webadmin/password", webserversPaths.get("sudo_password_path"),
            "Should have webservers sudo password path");
    }

    @Test
    @DisplayName("Should parse host-specific Vault paths")
    void testParseHostPaths() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);

        Map<String, String> web1Paths = vaultPaths.getHostPaths("web1.example.com");
        assertEquals("secret/data/ssh/web1-admin/private_key", web1Paths.get("ssh_key_path"),
            "Should have web1 SSH key path");
        assertEquals("secret/data/sudo/web1-admin/password", web1Paths.get("sudo_password_path"),
            "Should have web1 sudo password path");

        Map<String, String> db1Paths = vaultPaths.getHostPaths("db1.example.com");
        assertEquals("secret/data/ssh/postgres/private_key", db1Paths.get("ssh_key_path"),
            "Should have db1 SSH key path");
        assertEquals("secret/data/sudo/postgres/password", db1Paths.get("sudo_password_path"),
            "Should have db1 sudo password path");
    }

    @Test
    @DisplayName("Should apply Vault path priority correctly")
    void testVaultPathPriority() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);

        // web1.example.com has host-specific paths
        Map<String, String> web1Paths = vaultPaths.getPathsForHost("web1.example.com", "webservers");
        assertEquals("secret/data/ssh/web1-admin/private_key", web1Paths.get("ssh_key_path"),
            "web1 should use host-specific SSH key path");
        assertEquals("secret/data/sudo/web1-admin/password", web1Paths.get("sudo_password_path"),
            "web1 should use host-specific sudo password path");

        // web2.example.com has no host-specific paths, should use group paths
        Map<String, String> web2Paths = vaultPaths.getPathsForHost("web2.example.com", "webservers");
        assertEquals("secret/data/ssh/webadmin/private_key", web2Paths.get("ssh_key_path"),
            "web2 should use webservers group SSH key path");
        assertEquals("secret/data/sudo/webadmin/password", web2Paths.get("sudo_password_path"),
            "web2 should use webservers group sudo password path");

        // db2.example.com has no host or group-specific paths, should use global paths
        Map<String, String> db2Paths = vaultPaths.getPathsForHost("db2.example.com", "dbservers");
        assertEquals("secret/data/ssh/iacuser/private_key", db2Paths.get("ssh_key_path"),
            "db2 should use global SSH key path");
        assertEquals("secret/data/sudo/iacuser/password", db2Paths.get("sudo_password_path"),
            "db2 should use global sudo password path");
    }

    @Test
    @DisplayName("Should return empty map for nonexistent group")
    void testNonexistentGroup() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);

        Map<String, String> nonexistentPaths = vaultPaths.getGroupPaths("nonexistent");
        assertTrue(nonexistentPaths.isEmpty(), "Should return empty map for nonexistent group");
    }

    @Test
    @DisplayName("Should return empty map for nonexistent host")
    void testNonexistentHost() throws IOException {
        InputStream input = getClass().getResourceAsStream("/test-vault-config.ini");
        VaultConfigParser.VaultPaths vaultPaths = VaultConfigParser.parse(input);

        Map<String, String> nonexistentPaths = vaultPaths.getHostPaths("nonexistent.example.com");
        assertTrue(nonexistentPaths.isEmpty(), "Should return empty map for nonexistent host");
    }
}
