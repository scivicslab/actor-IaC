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

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Client for HashiCorp Vault API communication.
 * Supports reading secrets from Vault KV v2 engine.
 *
 * @author devteam@scivicslab.com
 */
public class VaultClient {
    private final VaultConfig config;
    private final HttpClient httpClient;

    /**
     * Creates a new VaultClient with the given configuration.
     *
     * @param config Vault configuration
     */
    public VaultClient(VaultConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Reads a secret from Vault.
     *
     * @param path Secret path (e.g., "secret/data/ssh/iacuser/private_key")
     * @return Secret value as String
     * @throws VaultException if Vault communication fails or secret not found
     */
    public String readSecret(String path) throws VaultException {
        try {
            String url = config.getAddress() + "/v1/" + path;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Vault-Token", config.getToken())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new VaultException("Secret not found at path: " + path);
            }

            if (response.statusCode() != 200) {
                throw new VaultException("Vault returned status " + response.statusCode() +
                        ": " + response.body());
            }

            return extractSecretValue(response.body());

        } catch (IOException | InterruptedException e) {
            throw new VaultException("Failed to read secret from Vault: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the secret value from Vault API response.
     *
     * For KV v2 engine, the response structure is:
     * {
     *   "data": {
     *     "data": {
     *       "value": "actual-secret-value"
     *     }
     *   }
     * }
     *
     * @param jsonResponse JSON response from Vault
     * @return Secret value
     * @throws VaultException if response format is invalid
     */
    @SuppressWarnings("unchecked")
    private String extractSecretValue(String jsonResponse) throws VaultException {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> response = yaml.load(jsonResponse);

            Object dataObj = response.get("data");
            if (!(dataObj instanceof Map)) {
                throw new VaultException("Invalid Vault response: 'data' field missing or not a map");
            }

            Map<String, Object> data = (Map<String, Object>) dataObj;
            Object innerDataObj = data.get("data");
            if (!(innerDataObj instanceof Map)) {
                throw new VaultException("Invalid Vault response: nested 'data' field missing or not a map");
            }

            Map<String, Object> innerData = (Map<String, Object>) innerDataObj;
            Object value = innerData.get("value");
            if (value == null) {
                throw new VaultException("Invalid Vault response: 'value' field missing");
            }

            return value.toString();

        } catch (Exception e) {
            throw new VaultException("Failed to parse Vault response: " + e.getMessage(), e);
        }
    }

    /**
     * Exception thrown when Vault operations fail.
     */
    public static class VaultException extends Exception {
        public VaultException(String message) {
            super(message);
        }

        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
