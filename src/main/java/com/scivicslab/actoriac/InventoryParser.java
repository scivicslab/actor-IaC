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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for Ansible inventory files in INI format.
 *
 * <p>This parser supports basic Ansible inventory file format with groups
 * and variables. Example:</p>
 *
 * <pre>
 * [webservers]
 * web1.example.com
 * web2.example.com
 *
 * [dbservers]
 * db1.example.com
 *
 * [all:vars]
 * ansible_user=admin
 * ansible_port=22
 * </pre>
 *
 * @author devteam@scivics-lab.com
 */
public class InventoryParser {

    /**
     * Parses an Ansible inventory file.
     *
     * @param input the input stream of the inventory file
     * @return the parsed inventory
     * @throws IOException if reading the file fails
     */
    public static Inventory parse(InputStream input) throws IOException {
        Inventory inventory = new Inventory();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String currentGroup = null;
            boolean inVarsSection = false;
            Map<String, String> currentVars = new HashMap<>();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Check for group header
                if (line.startsWith("[") && line.endsWith("]")) {
                    String groupDeclaration = line.substring(1, line.length() - 1);

                    // Check if it's a vars section
                    if (groupDeclaration.endsWith(":vars")) {
                        inVarsSection = true;
                        currentGroup = groupDeclaration.substring(0, groupDeclaration.length() - 5);
                        currentVars = new HashMap<>();
                    } else {
                        inVarsSection = false;
                        currentGroup = groupDeclaration;
                        inventory.addGroup(currentGroup);
                    }
                    continue;
                }

                // Process content based on section type
                if (inVarsSection) {
                    // Parse variable assignment
                    int equalsIndex = line.indexOf('=');
                    if (equalsIndex > 0) {
                        String key = line.substring(0, equalsIndex).trim();
                        String value = line.substring(equalsIndex + 1).trim();
                        currentVars.put(key, value);

                        // Apply vars to group
                        if ("all".equals(currentGroup)) {
                            inventory.addGlobalVar(key, value);
                        } else if (currentGroup != null) {
                            inventory.addGroupVar(currentGroup, key, value);
                        }
                    }
                } else if (currentGroup != null) {
                    // Parse host line with optional variables
                    // Format: hostname [key=value key=value ...]
                    String[] tokens = line.split("\\s+");
                    String hostname = tokens[0];
                    inventory.addHost(currentGroup, hostname);

                    // Parse host-specific variables
                    for (int i = 1; i < tokens.length; i++) {
                        String token = tokens[i];
                        int equalsIndex = token.indexOf('=');
                        if (equalsIndex > 0) {
                            String key = token.substring(0, equalsIndex).trim();
                            String value = token.substring(equalsIndex + 1).trim();
                            inventory.addHostVar(hostname, key, value);
                        }
                    }
                }
            }
        }

        return inventory;
    }

    /**
     * Represents a parsed Ansible inventory.
     */
    public static class Inventory {
        private final Map<String, List<String>> groups = new HashMap<>();
        private final Map<String, String> globalVars = new HashMap<>();
        private final Map<String, Map<String, String>> groupVars = new HashMap<>();
        private final Map<String, Map<String, String>> hostVars = new HashMap<>();

        public void addGroup(String groupName) {
            groups.putIfAbsent(groupName, new ArrayList<>());
        }

        public void addHost(String groupName, String hostname) {
            groups.computeIfAbsent(groupName, k -> new ArrayList<>()).add(hostname);
        }

        public void addGlobalVar(String key, String value) {
            globalVars.put(key, value);
        }

        public void addGroupVar(String groupName, String key, String value) {
            groupVars.computeIfAbsent(groupName, k -> new HashMap<>()).put(key, value);
        }

        public void addHostVar(String hostname, String key, String value) {
            hostVars.computeIfAbsent(hostname, k -> new HashMap<>()).put(key, value);
        }

        public List<String> getHosts(String groupName) {
            return groups.getOrDefault(groupName, new ArrayList<>());
        }

        public Map<String, String> getGlobalVars() {
            return new HashMap<>(globalVars);
        }

        public Map<String, String> getGroupVars(String groupName) {
            return new HashMap<>(groupVars.getOrDefault(groupName, new HashMap<>()));
        }

        public Map<String, String> getHostVars(String hostname) {
            return new HashMap<>(hostVars.getOrDefault(hostname, new HashMap<>()));
        }

        public Map<String, List<String>> getAllGroups() {
            return new HashMap<>(groups);
        }
    }
}
