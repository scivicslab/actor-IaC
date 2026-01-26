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
 * Tests for VaultClient.
 *
 * Note: These tests only verify basic configuration and error handling.
 * Integration tests with a real Vault server should be performed separately.
 *
 * @author devteam@scivicslab.com
 */
@DisplayName("VaultClient Tests")
class VaultClientTest {

    @Test
    @DisplayName("Should create VaultConfig with valid parameters")
    void testVaultConfigCreation() {
        VaultConfig config = new VaultConfig("http://127.0.0.1:8200", "test-token");

        assertEquals("http://127.0.0.1:8200", config.getAddress());
        assertEquals("test-token", config.getToken());
    }

    @Test
    @DisplayName("Should remove trailing slash from Vault address")
    void testVaultConfigNormalizesAddress() {
        VaultConfig config = new VaultConfig("http://127.0.0.1:8200/", "test-token");

        assertEquals("http://127.0.0.1:8200", config.getAddress(),
            "Trailing slash should be removed from address");
    }

    @Test
    @DisplayName("Should throw exception for null address")
    void testVaultConfigNullAddress() {
        assertThrows(IllegalArgumentException.class, () -> {
            new VaultConfig(null, "test-token");
        }, "Should throw IllegalArgumentException for null address");
    }

    @Test
    @DisplayName("Should throw exception for empty address")
    void testVaultConfigEmptyAddress() {
        assertThrows(IllegalArgumentException.class, () -> {
            new VaultConfig("", "test-token");
        }, "Should throw IllegalArgumentException for empty address");
    }

    @Test
    @DisplayName("Should throw exception for null token")
    void testVaultConfigNullToken() {
        assertThrows(IllegalArgumentException.class, () -> {
            new VaultConfig("http://127.0.0.1:8200", null);
        }, "Should throw IllegalArgumentException for null token");
    }

    @Test
    @DisplayName("Should throw exception for empty token")
    void testVaultConfigEmptyToken() {
        assertThrows(IllegalArgumentException.class, () -> {
            new VaultConfig("http://127.0.0.1:8200", "");
        }, "Should throw IllegalArgumentException for empty token");
    }

    @Test
    @DisplayName("Should create VaultClient with valid config")
    void testVaultClientCreation() {
        VaultConfig config = new VaultConfig("http://127.0.0.1:8200", "test-token");
        VaultClient client = new VaultClient(config);

        assertNotNull(client, "VaultClient should be created successfully");
    }

    @Test
    @DisplayName("Should mask token in VaultConfig toString")
    void testVaultConfigToStringMasksToken() {
        VaultConfig config = new VaultConfig("http://127.0.0.1:8200", "secret-token-123");
        String configString = config.toString();

        assertTrue(configString.contains("http://127.0.0.1:8200"),
            "toString should contain address");
        assertFalse(configString.contains("secret-token-123"),
            "toString should not expose the actual token");
        assertTrue(configString.contains("***"),
            "toString should mask token with ***");
    }
}
