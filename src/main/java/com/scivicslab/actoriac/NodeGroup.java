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

import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Manages a group of nodes based on an Ansible inventory file.
 *
 * <p>This is a pure POJO class that reads Ansible inventory files and creates
 * Node objects for a specific group. It does not depend on ActorSystem -
 * the responsibility of converting Node objects to actors belongs to the caller.</p>
 *
 * <p>Supports optional HashiCorp Vault integration for secure secret management.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Using Builder Pattern (Recommended)</h3>
 * <pre>{@code
 * // Simple inventory loading
 * NodeGroup nodeGroup = new NodeGroup.Builder()
 *     .withInventory(new FileInputStream("inventory.ini"))
 *     .build();
 *
 * // With Vault integration
 * VaultClient vaultClient = new VaultClient(vaultConfig);
 * NodeGroup nodeGroup = new NodeGroup.Builder()
 *     .withInventory(new FileInputStream("inventory.ini"))
 *     .withVaultConfig(new FileInputStream("vault-config.ini"), vaultClient)
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
    private VaultConfigParser.VaultPaths vaultPaths;
    private final VaultClient vaultClient;
    private EncryptedSecretConfig encryptedSecrets;

    /**
     * Builder for creating NodeGroup instances with fluent API.
     *
     * <p>This is the recommended way to create NodeGroup instances.</p>
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * NodeGroup nodeGroup = new NodeGroup.Builder()
     *     .withInventory(new FileInputStream("inventory.ini"))
     *     .withVaultConfig(new FileInputStream("vault-config.ini"), vaultClient)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private InventoryParser.Inventory inventory;
        private VaultConfigParser.VaultPaths vaultPaths;
        private VaultClient vaultClient;
        private EncryptedSecretConfig encryptedSecrets;

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
         * Loads a vault-config.ini file and associates a VaultClient.
         *
         * @param vaultConfigStream the input stream containing the vault-config.ini file
         * @param vaultClient the Vault client for secret management
         * @return this builder for method chaining
         * @throws IOException if reading the vault config fails
         */
        public Builder withVaultConfig(InputStream vaultConfigStream, VaultClient vaultClient) throws IOException {
            this.vaultPaths = VaultConfigParser.parse(vaultConfigStream);
            this.vaultClient = vaultClient;
            return this;
        }

        /**
         * Loads an encrypted secrets file (without Vault).
         *
         * <p>This is an alternative to Vault for managing secrets in encrypted form.</p>
         *
         * @param encryptedSecretsStream the input stream containing the encrypted secrets file
         * @param encryptionKey Base64-encoded encryption key (typically from environment variable)
         * @return this builder for method chaining
         * @throws IOException if reading or decrypting the secrets fails
         */
        public Builder withEncryptedSecrets(InputStream encryptedSecretsStream, String encryptionKey) throws IOException {
            this.encryptedSecrets = EncryptedSecretConfig.parse(encryptedSecretsStream, encryptionKey);
            return this;
        }

        /**
         * Builds the NodeGroup instance.
         *
         * @return a new NodeGroup instance with the configured settings
         */
        public NodeGroup build() {
            return new NodeGroup(inventory, vaultPaths, vaultClient, encryptedSecrets);
        }
    }

    /**
     * Constructs a NodeGroup without Vault integration.
     *
     * <p><strong>Note:</strong> Consider using {@link Builder} for a more fluent API.</p>
     */
    public NodeGroup() {
        this(null);
    }

    /**
     * Constructs a NodeGroup with optional Vault client.
     *
     * <p><strong>Note:</strong> Consider using {@link Builder} for a more fluent API.</p>
     *
     * @param vaultClient the Vault client for secret management (can be null)
     */
    public NodeGroup(VaultClient vaultClient) {
        this.vaultClient = vaultClient;
    }

    /**
     * Private constructor used by Builder.
     *
     * @param inventory the parsed inventory
     * @param vaultPaths the parsed Vault configuration paths
     * @param vaultClient the Vault client for secret management (can be null)
     * @param encryptedSecrets the encrypted secret configuration (can be null)
     */
    private NodeGroup(InventoryParser.Inventory inventory, VaultConfigParser.VaultPaths vaultPaths,
                      VaultClient vaultClient, EncryptedSecretConfig encryptedSecrets) {
        this.inventory = inventory;
        this.vaultPaths = vaultPaths;
        this.vaultClient = vaultClient;
        this.encryptedSecrets = encryptedSecrets;
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
     * @param system the actor system for workflow execution (required for Node's Interpreter capabilities)
     * @return the list of created Node objects
     * @throws IllegalStateException if inventory has not been loaded
     * @throws RuntimeException if Vault secret retrieval fails
     */
    public List<Node> createNodesForGroup(String groupName, IIActorSystem system) {
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

            // Fetch secrets from Vault or encrypted config
            String sshKeyContent = null;
            String sshPassphrase = null;
            String sudoPassword = null;

            // Priority: Vault > Encrypted Secrets
            if (vaultClient != null && vaultPaths != null) {
                // Fetch from Vault
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

                // Fetch SSH passphrase from Vault
                String sshPassphrasePath = vaultPathsForHost.get("ssh_passphrase_path");
                if (sshPassphrasePath != null) {
                    try {
                        sshPassphrase = vaultClient.readSecret(sshPassphrasePath);
                    } catch (VaultClient.VaultException e) {
                        throw new RuntimeException(
                            "Failed to read SSH passphrase from Vault for host " + hostname + ": " + e.getMessage(), e);
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
            } else if (encryptedSecrets != null) {
                // Fetch from encrypted secrets
                Map<String, String> secrets = encryptedSecrets.getSecretsForHost(hostname, groupName);

                sshKeyContent = secrets.get("ssh_key");
                sshPassphrase = secrets.get("ssh_passphrase");
                sudoPassword = secrets.get("sudo_password");
            }

            Node node = new Node(hostname, user, port, identityFile, sshKeyContent, sshPassphrase, sudoPassword, system);
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
        return String.format("NodeGroup{vaultEnabled=%s, groups=%s}",
            vaultClient != null,
            inventory != null ? inventory.getAllGroups().keySet() : "[]");
    }
}
