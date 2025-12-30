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

    /**
     * Builder for creating NodeGroup instances with fluent API.
     *
     * <p>This is the recommended way to create NodeGroup instances.</p>
     *
     * <h3>Example:</h3>
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
            // Merge vars with priority: host vars > group vars > global vars
            Map<String, String> effectiveVars = new HashMap<>(globalVars);
            effectiveVars.putAll(groupVars);
            effectiveVars.putAll(inventory.getHostVars(hostname));

            // Extract connection parameters for this host
            String user = effectiveVars.getOrDefault("ansible_user", System.getProperty("user.name"));
            int port = Integer.parseInt(effectiveVars.getOrDefault("ansible_port", "22"));

            // Create Node - SSH authentication is handled by ssh-agent
            Node node = new Node(hostname, user, port);
            nodes.add(node);
        }

        return nodes;
    }

    /**
     * Gets the inventory object.
     *
     * @return the loaded inventory, or null if not loaded
     */
    public InventoryParser.Inventory getInventory() {
        return inventory;
    }

    @Override
    public String toString() {
        return String.format("NodeGroup{groups=%s}",
            inventory != null ? inventory.getAllGroups().keySet() : "[]");
    }
}
