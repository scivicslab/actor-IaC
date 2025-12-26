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

import com.scivicslab.pojoactor.ActorRef;
import com.scivicslab.pojoactor.ActorSystem;

import java.io.FileInputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating HashiCorp Vault integration with actor-IaC.
 *
 * <p>This example shows how to:
 * <ul>
 *   <li>Initialize VaultClient with credentials</li>
 *   <li>Load inventory and vault configuration files</li>
 *   <li>Create node actors with Vault-provided secrets</li>
 *   <li>Execute sudo commands using Vault-managed passwords</li>
 * </ul>
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>HashiCorp Vault server running (dev mode: vault server -dev)</li>
 *   <li>Environment variables set: VAULT_ADDR, VAULT_TOKEN</li>
 *   <li>Secrets stored in Vault:
 *     <pre>
 *     vault kv put secret/ssh/iacuser/private_key value=@~/.ssh/id_rsa
 *     vault kv put secret/sudo/iacuser/password value="MySudoPassword"
 *     </pre>
 *   </li>
 *   <li>inventory.ini and vault-config.ini files configured</li>
 * </ol>
 *
 * @author devteam@scivics-lab.com
 */
public class VaultExample {

    public static void main(String[] args) {
        try {
            System.out.println("=== actor-IaC Vault Integration Example ===\n");

            // Step 1: Initialize Vault client
            System.out.println("Step 1: Initializing Vault client...");
            String vaultAddr = System.getenv("VAULT_ADDR");
            String vaultToken = System.getenv("VAULT_TOKEN");

            if (vaultAddr == null || vaultToken == null) {
                System.err.println("Error: VAULT_ADDR and VAULT_TOKEN environment variables must be set");
                System.err.println("Example:");
                System.err.println("  export VAULT_ADDR='http://127.0.0.1:8200'");
                System.err.println("  export VAULT_TOKEN='dev-token'");
                System.exit(1);
            }

            VaultConfig vaultConfig = new VaultConfig(vaultAddr, vaultToken);
            VaultClient vaultClient = new VaultClient(vaultConfig);
            System.out.println("Vault client initialized: " + vaultAddr + "\n");

            // Step 2: Create cluster using Builder pattern
            System.out.println("Step 2: Creating cluster with Builder pattern...");
            Cluster cluster = new Cluster.Builder()
                    .withInventory(new FileInputStream("inventory.ini"))
                    .withVaultConfig(new FileInputStream("vault-config.ini"), vaultClient)
                    .build();
            System.out.println("Cluster created with inventory and Vault configuration loaded\n");

            // Step 3: Create Node objects for webservers group
            System.out.println("Step 3: Creating Node objects for webservers group...");
            System.out.println("(This will fetch SSH keys and sudo passwords from Vault)");
            List<Node> nodes = cluster.createNodesForGroup("webservers");
            System.out.println("Created " + nodes.size() + " Node objects\n");

            // Step 4: Convert Node objects to actors
            System.out.println("Step 4: Converting Node objects to actors...");
            ActorSystem system = new ActorSystem("vault-iac-example", 4);
            List<ActorRef<Node>> webservers = nodes.stream()
                    .map(node -> system.actorOf("node-" + node.getHostname().replace(".", "-"), node))
                    .toList();
            System.out.println("Created " + webservers.size() + " node actors\n");

            // Step 5: Execute a regular command
            System.out.println("Step 5: Executing hostname command on all webservers...");
            List<CompletableFuture<Node.CommandResult>> hostnameFutures = webservers.stream()
                    .map(nodeActor -> nodeActor.ask(node -> {
                        try {
                            System.out.println("  Connecting to " + node.getHostname() + "...");
                            return node.executeCommand("hostname");
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to execute command", e);
                        }
                    }))
                    .toList();

            // Wait for all results
            CompletableFuture.allOf(hostnameFutures.toArray(new CompletableFuture[0])).join();

            for (int i = 0; i < webservers.size(); i++) {
                Node.CommandResult result = hostnameFutures.get(i).get();
                ActorRef<Node> nodeActor = webservers.get(i);
                String hostname = nodeActor.ask(Node::getHostname).get();
                System.out.println("  " + hostname + ": " + result.getStdout());
            }
            System.out.println();

            // Step 6: Execute a sudo command
            System.out.println("Step 6: Executing sudo command (using Vault password)...");
            if (!webservers.isEmpty()) {
                ActorRef<Node> firstNode = webservers.get(0);
                String nodeName = firstNode.ask(Node::getHostname).get();
                System.out.println("  Executing 'sudo whoami' on " + nodeName + "...");

                CompletableFuture<Node.CommandResult> sudoResult = firstNode.ask(node -> {
                    try {
                        return node.executeSudoCommand("whoami");
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to execute sudo command", e);
                    }
                });

                Node.CommandResult result = sudoResult.get();
                System.out.println("  Result: " + result.getStdout());
                System.out.println("  Exit code: " + result.getExitCode());

                if (result.isSuccess()) {
                    System.out.println("  ✓ Sudo command executed successfully!");
                } else {
                    System.out.println("  ✗ Sudo command failed");
                    System.out.println("  Error: " + result.getStderr());
                }
            }
            System.out.println();

            // Step 7: Cleanup
            System.out.println("Step 7: Cleaning up...");

            // Clean up temporary SSH key files
            for (ActorRef<Node> nodeActor : webservers) {
                nodeActor.tell(Node::cleanup);
            }

            system.terminate();
            System.out.println("Actor system terminated");

            System.out.println("\n=== Example completed successfully ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
