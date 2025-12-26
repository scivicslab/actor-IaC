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
 * Manages a cluster of nodes based on an Ansible inventory file.
 *
 * <p>This is a pure POJO class that reads Ansible inventory files and creates
 * Node objects with their configuration. It does not depend on ActorSystem -
 * the responsibility of converting Node objects to actors belongs to the caller.</p>
 *
 * <p>Supports optional HashiCorp Vault integration for secure secret management.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Cluster {

    private InventoryParser.Inventory inventory;
    private VaultConfigParser.VaultPaths vaultPaths;
    private final VaultClient vaultClient;

    /**
     * Constructs a Cluster without Vault integration.
     */
    public Cluster() {
        this(null);
    }

    /**
     * Constructs a Cluster with optional Vault client.
     *
     * @param vaultClient the Vault client for secret management (can be null)
     */
    public Cluster(VaultClient vaultClient) {
        this.vaultClient = vaultClient;
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
     * Loads a vault-config.ini file from an input stream.
     *
     * <p>This method is only effective if a VaultClient was provided during construction.</p>
     *
     * @param vaultConfigStream the input stream containing the vault-config.ini file
     * @throws IOException if reading the vault config fails
     */
    public void loadVaultConfig(InputStream vaultConfigStream) throws IOException {
        this.vaultPaths = VaultConfigParser.parse(vaultConfigStream);
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
            String identityFile = effectiveVars.get("ansible_ssh_private_key_file");

            // Fetch secrets from Vault if configured
            String sshKeyContent = null;
            String sudoPassword = null;

            if (vaultClient != null && vaultPaths != null) {
                Map<String, String> vaultPathsForHost = vaultPaths.getPathsForHost(hostname, groupName);

                // Fetch SSH key from Vault
                String sshKeyPath = vaultPathsForHost.get("ssh_key_path");
                if (sshKeyPath != null) {
                    try {
                        sshKeyContent = vaultClient.readSecret(sshKeyPath);
                    } catch (VaultClient.VaultException e) {
                        throw new RuntimeException(
                            "Failed to read SSH key from Vault for host " + hostname + ": " + e.getMessage(), e);
                    }
                }

                // Fetch sudo password from Vault
                String sudoPasswordPath = vaultPathsForHost.get("sudo_password_path");
                if (sudoPasswordPath != null) {
                    try {
                        sudoPassword = vaultClient.readSecret(sudoPasswordPath);
                    } catch (VaultClient.VaultException e) {
                        throw new RuntimeException(
                            "Failed to read sudo password from Vault for host " + hostname + ": " + e.getMessage(), e);
                    }
                }
            }

            Node node = new Node(hostname, user, port, identityFile, sshKeyContent, sudoPassword);
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

    /**
     * Gets the Vault client used by this cluster.
     *
     * @return the Vault client, or null if not configured
     */
    public VaultClient getVaultClient() {
        return vaultClient;
    }

    @Override
    public String toString() {
        return String.format("Cluster{vaultEnabled=%s, groups=%s}",
            vaultClient != null,
            inventory != null ? inventory.getAllGroups().keySet() : "[]");
    }
}
