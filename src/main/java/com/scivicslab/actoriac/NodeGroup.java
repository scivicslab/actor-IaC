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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages a group of nodes based on an Ansible inventory file.
 *
 * <p>This is a pure POJO class that reads Ansible inventory files and creates
 * Node objects for a specific group. It does not depend on ActorSystem -
 * the responsibility of converting Node objects to actors belongs to the caller.</p>
 *
 * <p>SSH authentication is handled by ssh-agent. Make sure ssh-agent is running
 * and your SSH key is added before creating nodes.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Using Builder Pattern (Recommended)</h3>
 * <pre>{@code
 * NodeGroup nodeGroup = new NodeGroup.Builder()
 *     .withInventory(new FileInputStream("inventory.ini"))
 *     .build();
 * }</pre>
 *
 * <h3>Legacy Constructor Pattern</h3>
 * <pre>{@code
 * NodeGroup nodeGroup = new NodeGroup();
 * nodeGroup.loadInventory(new FileInputStream("inventory.ini"));
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeGroup {

    private InventoryParser.Inventory inventory;
    private String sshPassword;
    private List<String> hostLimit;

    /**
     * Builder for creating NodeGroup instances with fluent API.
     *
     * <p>This is the recommended way to create NodeGroup instances.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * NodeGroup nodeGroup = new NodeGroup.Builder()
     *     .withInventory(new FileInputStream("inventory.ini"))
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private InventoryParser.Inventory inventory;

        /**
         * Loads an Ansible inventory file.
         *
         * @param inventoryStream the input stream containing the inventory file
         * @return this builder for method chaining
         * @throws IOException if reading the inventory fails
         */
        public Builder withInventory(InputStream inventoryStream) throws IOException {
            this.inventory = InventoryParser.parse(inventoryStream);
            return this;
        }

        /**
         * Builds the NodeGroup instance.
         *
         * @return a new NodeGroup instance with the configured settings
         */
        public NodeGroup build() {
            return new NodeGroup(inventory);
        }
    }

    /**
     * Constructs an empty NodeGroup.
     *
     * <p><strong>Note:</strong> Consider using {@link Builder} for a more fluent API.</p>
     */
    public NodeGroup() {
    }

    /**
     * Private constructor used by Builder.
     *
     * @param inventory the parsed inventory
     */
    private NodeGroup(InventoryParser.Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * Loads an inventory file from an input stream.
     *
     * @param inventoryStream the input stream containing the inventory file
     * @throws IOException if reading the inventory fails
     */
    public void loadInventory(InputStream inventoryStream) throws IOException {
        this.inventory = InventoryParser.parse(inventoryStream);
    }

    /**
     * Creates Node objects for all hosts in the specified group.
     *
     * <p>This method reads the group from the inventory, applies global and
     * group-specific variables, and creates a Node POJO for each host.</p>
     *
     * <p>If Vault integration is configured, this method will fetch SSH keys and
     * sudo passwords from Vault based on the vault-config.ini settings.</p>
     *
     * <p>Note: This method returns plain Node objects, not actors. The caller
     * is responsible for converting them to actors using ActorSystem.actorOf()
     * if needed.</p>
     *
     * @param groupName the name of the group from the inventory file
     * @return the list of created Node objects
     * @throws IllegalStateException if inventory has not been loaded
     * @throws RuntimeException if Vault secret retrieval fails
     */
    public List<Node> createNodesForGroup(String groupName) {
        if (inventory == null) {
            throw new IllegalStateException("Inventory not loaded. Call loadInventory() first.");
        }

        List<String> hosts = inventory.getHosts(groupName);
        List<Node> nodes = new ArrayList<>();

        // Get base variables
        Map<String, String> globalVars = inventory.getGlobalVars();
        Map<String, String> groupVars = inventory.getGroupVars(groupName);

        // Create nodes
        for (String hostname : hosts) {
            // Apply host limit if set
            if (hostLimit != null && !hostLimit.contains(hostname)) {
                continue;
            }
            // Merge vars with priority: host vars > group vars > global vars
            Map<String, String> effectiveVars = new HashMap<>(globalVars);
            effectiveVars.putAll(groupVars);
            effectiveVars.putAll(inventory.getHostVars(hostname));

            // Extract connection parameters for this host
            // Use ansible_host if specified, otherwise use the logical hostname
            String actualHost = effectiveVars.getOrDefault("ansible_host", hostname);
            String user = effectiveVars.getOrDefault("ansible_user", System.getProperty("user.name"));
            int port = Integer.parseInt(effectiveVars.getOrDefault("ansible_port", "22"));

            // Check if local connection mode is requested (like Ansible's ansible_connection=local)
            String connection = effectiveVars.getOrDefault("ansible_connection", "ssh");
            boolean localMode = "local".equalsIgnoreCase(connection);

            // Create Node using actualHost (IP or DNS) for SSH connection
            Node node = new Node(actualHost, user, port, localMode, sshPassword);
            nodes.add(node);
        }

        return nodes;
    }

    /**
     * Creates a single Node for localhost execution.
     *
     * <p>This method creates a Node configured for local execution without requiring
     * an inventory file. Useful for development, testing, or single-host scenarios.</p>
     *
     * <p>The node is created with:</p>
     * <ul>
     *   <li>hostname: "localhost"</li>
     *   <li>user: current system user</li>
     *   <li>localMode: true (uses ProcessBuilder instead of SSH)</li>
     * </ul>
     *
     * @return a list containing a single localhost Node
     */
    public List<Node> createLocalNode() {
        Node localNode = new Node("localhost",
                                   System.getProperty("user.name"),
                                   22,
                                   true);  // localMode = true
        return List.of(localNode);
    }

    /**
     * Gets the inventory object.
     *
     * @return the loaded inventory, or null if not loaded
     */
    public InventoryParser.Inventory getInventory() {
        return inventory;
    }

    /**
     * Sets the SSH password for all nodes in this group.
     *
     * <p>When set, nodes will use password authentication instead of
     * ssh-agent key authentication.</p>
     *
     * @param password the SSH password to use for all nodes
     */
    public void setSshPassword(String password) {
        this.sshPassword = password;
    }

    /**
     * Gets the SSH password.
     *
     * @return the SSH password, or null if not set
     */
    public String getSshPassword() {
        return sshPassword;
    }

    /**
     * Sets the host limit to restrict execution to specific hosts.
     *
     * <p>When set, only hosts in this list will be included when creating nodes.
     * This is similar to Ansible's --limit option.</p>
     *
     * @param limitString comma-separated list of hosts (e.g., "192.168.5.15,192.168.5.16")
     */
    public void setHostLimit(String limitString) {
        if (limitString == null || limitString.trim().isEmpty()) {
            this.hostLimit = null;
        } else {
            this.hostLimit = new ArrayList<>();
            for (String host : limitString.split(",")) {
                String trimmed = host.trim();
                if (!trimmed.isEmpty()) {
                    this.hostLimit.add(trimmed);
                }
            }
        }
    }

    /**
     * Gets the host limit.
     *
     * @return the list of limited hosts, or null if no limit is set
     */
    public List<String> getHostLimit() {
        return hostLimit;
    }

    @Override
    public String toString() {
        return String.format("NodeGroup{groups=%s}",
            inventory != null ? inventory.getAllGroups().keySet() : "[]");
    }
}
