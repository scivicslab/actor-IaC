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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parser for Ansible inventory files in INI format.
 *
 * <p>This parser supports a subset of Ansible inventory file format.
 * actor-IaC supports both Ansible-compatible syntax (ansible_*) and
 * native syntax (actoriac_*).</p>
 *
 * <h2>Supported Features</h2>
 * <ul>
 *   <li>Groups: {@code [groupname]}</li>
 *   <li>Group variables: {@code [groupname:vars]}</li>
 *   <li>Global variables: {@code [all:vars]}</li>
 *   <li>Host-specific variables: {@code hostname key=value}</li>
 *   <li>Comments: lines starting with {@code #} or {@code ;}</li>
 * </ul>
 *
 * <h2>Supported Variables</h2>
 * <table border="1">
 *   <caption>Supported inventory variables</caption>
 *   <tr><th>actor-IaC</th><th>Ansible</th><th>Description</th></tr>
 *   <tr><td>actoriac_host</td><td>ansible_host</td><td>Actual hostname/IP to connect</td></tr>
 *   <tr><td>actoriac_user</td><td>ansible_user</td><td>SSH username</td></tr>
 *   <tr><td>actoriac_port</td><td>ansible_port</td><td>SSH port</td></tr>
 *   <tr><td>actoriac_connection</td><td>ansible_connection</td><td>Connection type (ssh/local)</td></tr>
 * </table>
 *
 * <h2>Unsupported Ansible Features</h2>
 * <p>The following Ansible features are NOT supported and will generate warnings:</p>
 * <ul>
 *   <li>Children groups: {@code [group:children]}</li>
 *   <li>Range patterns: {@code web[01:50].example.com}</li>
 *   <li>Privilege escalation: {@code ansible_become}, {@code ansible_become_user}</li>
 *   <li>Python interpreter: {@code ansible_python_interpreter}</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 */
public class InventoryParser {

    /** Pattern to detect Ansible range notation like [01:50] or [a:z] */
    private static final Pattern RANGE_PATTERN = Pattern.compile(".*\\[[0-9a-zA-Z]+:[0-9a-zA-Z]+\\].*");

    /** Set of supported variable suffixes (without prefix) */
    private static final Set<String> SUPPORTED_VAR_SUFFIXES = Set.of(
        "host", "user", "port", "connection"
    );

    /** Set of known unsupported Ansible variables that should trigger warnings */
    private static final Set<String> UNSUPPORTED_ANSIBLE_VARS = Set.of(
        "ansible_become",
        "ansible_become_user",
        "ansible_become_pass",
        "ansible_become_method",
        "ansible_become_flags",
        "ansible_python_interpreter",
        "ansible_shell_type",
        "ansible_shell_executable",
        "ansible_ssh_private_key_file",
        "ansible_ssh_common_args",
        "ansible_ssh_extra_args",
        "ansible_ssh_pipelining",
        "ansible_ssh_pass",
        "ansible_sudo",
        "ansible_sudo_pass"
    );

    /**
     * Parses an Ansible inventory file.
     *
     * <p>This method collects warnings for unsupported Ansible features.
     * Warnings are stored in the returned {@link ParseResult} and should be
     * logged by the caller using the appropriate logging mechanism (e.g.,
     * {@link com.scivicslab.actoriac.log.DistributedLogStore}).</p>
     *
     * @param input the input stream of the inventory file
     * @return the parse result containing inventory and any warnings
     * @throws IOException if reading the file fails
     */
    public static ParseResult parse(InputStream input) throws IOException {
        Inventory inventory = new Inventory();
        List<String> warnings = new ArrayList<>();
        Set<String> warnedVars = new HashSet<>();
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String currentGroup = null;
            boolean inVarsSection = false;
            Map<String, String> currentVars = new HashMap<>();

            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Check for group header
                if (line.startsWith("[") && line.endsWith("]")) {
                    String groupDeclaration = line.substring(1, line.length() - 1);

                    // Check for unsupported :children syntax
                    if (groupDeclaration.endsWith(":children")) {
                        warnings.add(String.format(
                            "Line %d: [%s] - ':children' groups are not supported in actor-IaC. " +
                            "Please list hosts directly in each group instead.",
                            lineNumber, groupDeclaration));
                        currentGroup = null;
                        inVarsSection = false;
                        continue;
                    }

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

                        // Check for unsupported variables
                        checkUnsupportedVariable(key, lineNumber, warnedVars, warnings);

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

                    // Check for unsupported range notation
                    if (RANGE_PATTERN.matcher(hostname).matches()) {
                        warnings.add(String.format(
                            "Line %d: '%s' - Range patterns like [01:50] are not supported in actor-IaC. " +
                            "Please list each host individually.",
                            lineNumber, hostname));
                        continue;
                    }

                    inventory.addHost(currentGroup, hostname);

                    // Parse host-specific variables
                    for (int i = 1; i < tokens.length; i++) {
                        String token = tokens[i];
                        int equalsIndex = token.indexOf('=');
                        if (equalsIndex > 0) {
                            String key = token.substring(0, equalsIndex).trim();
                            String value = token.substring(equalsIndex + 1).trim();

                            // Check for unsupported variables
                            checkUnsupportedVariable(key, lineNumber, warnedVars, warnings);

                            inventory.addHostVar(hostname, key, value);
                        }
                    }
                }
            }
        }

        return new ParseResult(inventory, warnings);
    }

    /**
     * Checks if a variable is unsupported and adds a warning if so.
     *
     * @param key the variable name
     * @param lineNumber the line number for error reporting
     * @param warnedVars set of already warned variables (to avoid duplicate warnings)
     * @param warnings list to add warnings to
     */
    private static void checkUnsupportedVariable(String key, int lineNumber,
                                                  Set<String> warnedVars, List<String> warnings) {
        // Check for known unsupported Ansible variables
        if (UNSUPPORTED_ANSIBLE_VARS.contains(key)) {
            if (!warnedVars.contains(key)) {
                warnedVars.add(key);
                String suggestion = getUnsupportedVarSuggestion(key);
                warnings.add(String.format(
                    "Line %d: '%s' is not supported in actor-IaC. %s",
                    lineNumber, key, suggestion));
            }
            return;
        }

        // Check for ansible_* or actoriac_* variables that are not in the supported list
        if (key.startsWith("ansible_") || key.startsWith("actoriac_")) {
            String suffix = key.startsWith("ansible_")
                ? key.substring("ansible_".length())
                : key.substring("actoriac_".length());

            if (!SUPPORTED_VAR_SUFFIXES.contains(suffix) && !warnedVars.contains(key)) {
                warnedVars.add(key);
                warnings.add(String.format(
                    "Line %d: '%s' is not a recognized actor-IaC variable. " +
                    "Supported variables are: actoriac_host, actoriac_user, actoriac_port, actoriac_connection " +
                    "(or their ansible_* equivalents).",
                    lineNumber, key));
            }
        }
    }

    /**
     * Returns a helpful suggestion for unsupported Ansible variables.
     */
    private static String getUnsupportedVarSuggestion(String key) {
        if (key.startsWith("ansible_become") || key.equals("ansible_sudo") || key.equals("ansible_sudo_pass")) {
            return "For privilege escalation, use the SUDO_PASSWORD environment variable and the executeSudoCommand() method in workflows.";
        }
        if (key.equals("ansible_python_interpreter")) {
            return "actor-IaC executes commands directly via SSH without Python. This variable is not needed.";
        }
        if (key.startsWith("ansible_ssh_")) {
            return "SSH configuration should be done via ~/.ssh/config or ssh-agent.";
        }
        return "This Ansible feature is not implemented in actor-IaC.";
    }

    /**
     * Result of parsing an inventory file.
     *
     * <p>Contains the parsed inventory and any warnings generated during parsing.
     * Warnings should be logged by the caller using the appropriate logging mechanism.</p>
     */
    public static class ParseResult {
        private final Inventory inventory;
        private final List<String> warnings;

        /**
         * Constructs a new ParseResult.
         *
         * @param inventory the parsed inventory
         * @param warnings list of warning messages
         */
        public ParseResult(Inventory inventory, List<String> warnings) {
            this.inventory = inventory;
            this.warnings = warnings;
        }

        /**
         * Gets the parsed inventory.
         *
         * @return the inventory
         */
        public Inventory getInventory() {
            return inventory;
        }

        /**
         * Gets the list of warnings generated during parsing.
         *
         * @return list of warning messages (may be empty)
         */
        public List<String> getWarnings() {
            return warnings;
        }

        /**
         * Checks if any warnings were generated.
         *
         * @return true if there are warnings
         */
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
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
