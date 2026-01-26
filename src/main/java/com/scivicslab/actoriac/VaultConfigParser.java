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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for vault-config.ini files.
 * Supports global, group-specific, and host-specific Vault path configurations.
 *
 * @author devteam@scivicslab.com
 */
public class VaultConfigParser {

    /**
     * Parses a vault-config.ini file.
     *
     * @param input InputStream of the vault-config.ini file
     * @return VaultPaths object containing all Vault path configurations
     * @throws IOException if file reading fails
     */
    public static VaultPaths parse(InputStream input) throws IOException {
        VaultPaths vaultPaths = new VaultPaths();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            String currentSection = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Section header
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    continue;
                }

                // Key-value pair
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0 && currentSection != null) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();

                    if (currentSection.equals("vault:all")) {
                        vaultPaths.addGlobalPath(key, value);
                    } else if (currentSection.startsWith("vault:host:")) {
                        String hostname = currentSection.substring("vault:host:".length());
                        vaultPaths.addHostPath(hostname, key, value);
                    } else if (currentSection.startsWith("vault:")) {
                        String groupName = currentSection.substring("vault:".length());
                        vaultPaths.addGroupPath(groupName, key, value);
                    }
                }
            }
        }

        return vaultPaths;
    }

    /**
     * Container for Vault path configurations.
     */
    public static class VaultPaths {
        private final Map<String, String> globalPaths = new HashMap<>();
        private final Map<String, Map<String, String>> groupPaths = new HashMap<>();
        private final Map<String, Map<String, String>> hostPaths = new HashMap<>();

        /**
         * Adds a global Vault path.
         *
         * @param key Path key (e.g., "ssh_key_path")
         * @param value Vault path (e.g., "secret/data/ssh/iacuser/private_key")
         */
        public void addGlobalPath(String key, String value) {
            globalPaths.put(key, value);
        }

        /**
         * Adds a group-specific Vault path.
         *
         * @param groupName Group name
         * @param key Path key
         * @param value Vault path
         */
        public void addGroupPath(String groupName, String key, String value) {
            groupPaths.computeIfAbsent(groupName, k -> new HashMap<>()).put(key, value);
        }

        /**
         * Adds a host-specific Vault path.
         *
         * @param hostname Hostname
         * @param key Path key
         * @param value Vault path
         */
        public void addHostPath(String hostname, String key, String value) {
            hostPaths.computeIfAbsent(hostname, k -> new HashMap<>()).put(key, value);
        }

        /**
         * Gets Vault paths for a specific host, applying priority rules.
         * Priority: host-specific > group-specific > global
         *
         * @param hostname Hostname
         * @param groupNames Group names this host belongs to
         * @return Map of Vault paths for this host
         */
        public Map<String, String> getPathsForHost(String hostname, String... groupNames) {
            Map<String, String> result = new HashMap<>(globalPaths);

            // Apply group paths (later groups override earlier ones)
            for (String groupName : groupNames) {
                Map<String, String> groupPathMap = groupPaths.get(groupName);
                if (groupPathMap != null) {
                    result.putAll(groupPathMap);
                }
            }

            // Apply host-specific paths (highest priority)
            Map<String, String> hostPathMap = hostPaths.get(hostname);
            if (hostPathMap != null) {
                result.putAll(hostPathMap);
            }

            return result;
        }

        /**
         * Gets global Vault paths.
         *
         * @return Map of global paths
         */
        public Map<String, String> getGlobalPaths() {
            return new HashMap<>(globalPaths);
        }

        /**
         * Gets group-specific Vault paths.
         *
         * @param groupName Group name
         * @return Map of group paths, or empty map if group not found
         */
        public Map<String, String> getGroupPaths(String groupName) {
            return new HashMap<>(groupPaths.getOrDefault(groupName, new HashMap<>()));
        }

        /**
         * Gets host-specific Vault paths.
         *
         * @param hostname Hostname
         * @return Map of host paths, or empty map if host not found
         */
        public Map<String, String> getHostPaths(String hostname) {
            return new HashMap<>(hostPaths.getOrDefault(hostname, new HashMap<>()));
        }
    }
}
