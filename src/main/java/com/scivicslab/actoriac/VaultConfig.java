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

/**
 * Configuration for HashiCorp Vault connection.
 *
 * @author devteam@scivics-lab.com
 */
public class VaultConfig {
    private final String address;
    private final String token;

    /**
     * Creates a new VaultConfig.
     *
     * @param address Vault server address (e.g., "http://127.0.0.1:8200")
     * @param token Vault authentication token
     */
    public VaultConfig(String address, String token) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Vault address cannot be null or empty");
        }
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Vault token cannot be null or empty");
        }
        this.address = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        this.token = token;
    }

    /**
     * Gets the Vault server address.
     *
     * @return Vault server address
     */
    public String getAddress() {
        return address;
    }

    /**
     * Gets the Vault authentication token.
     *
     * @return Vault token
     */
    public String getToken() {
        return token;
    }

    @Override
    public String toString() {
        return "VaultConfig{address='" + address + "', token='***'}";
    }
}
