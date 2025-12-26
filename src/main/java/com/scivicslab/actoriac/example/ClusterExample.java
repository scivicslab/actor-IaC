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

package com.scivicslab.actoriac.example;

import com.scivicslab.actoriac.Cluster;
import com.scivicslab.actoriac.Node;
import com.scivicslab.pojoactor.ActorRef;
import com.scivicslab.pojoactor.ActorSystem;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating basic Cluster and Node usage.
 *
 * <p>This example shows how to:
 * <ul>
 * <li>Load an Ansible inventory file</li>
 * <li>Create node actors for a group</li>
 * <li>Execute commands on all nodes concurrently using the actor model</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 */
public class ClusterExample {

    public static void main(String[] args) {
        System.out.println("=== actor-IaC Cluster Example ===\n");

        // Create an ActorSystem with 4 worker threads
        ActorSystem actorSystem = new ActorSystem("iac-system", 4);

        try {
            // Create a cluster
            Cluster cluster = new Cluster(actorSystem);
            System.out.println("Created cluster with actor system");

            // Load inventory file
            InputStream inventoryStream = ClusterExample.class
                .getResourceAsStream("/example-inventory.ini");

            if (inventoryStream == null) {
                System.err.println("ERROR: example-inventory.ini not found in resources");
                System.err.println("Please create an inventory file to run this example");
                return;
            }

            cluster.loadInventory(inventoryStream);
            System.out.println("Loaded inventory file");
            System.out.println("Available groups: " +
                cluster.getInventory().getAllGroups().keySet());

            // Create node actors for webservers group
            List<ActorRef<Node>> webservers = cluster.createNodesForGroup("webservers");
            System.out.println("\nCreated " + webservers.size() +
                " node actors for webservers group");

            // Example: Execute 'hostname' command on all webservers concurrently
            System.out.println("\nExecuting 'echo hello' on all webservers...");

            List<CompletableFuture<Node.CommandResult>> futures = webservers.stream()
                .map(nodeActor -> nodeActor.ask(node -> {
                    try {
                        return node.executeCommand("echo 'Hello from ' && hostname");
                    } catch (Exception e) {
                        return new Node.CommandResult("", e.getMessage(), -1);
                    }
                }))
                .toList();

            // Wait for all commands to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Display results
            System.out.println("\nResults:");
            for (int i = 0; i < futures.size(); i++) {
                Node.CommandResult result = futures.get(i).get();
                System.out.println("Node " + (i + 1) + ":");
                System.out.println("  Exit Code: " + result.getExitCode());
                System.out.println("  Output: " + result.getStdout());
                if (!result.getStderr().isEmpty()) {
                    System.out.println("  Error: " + result.getStderr());
                }
            }

            System.out.println("\nCluster info: " + cluster);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clean up
            actorSystem.terminate();
            System.out.println("\nActor system terminated");
        }
    }
}
