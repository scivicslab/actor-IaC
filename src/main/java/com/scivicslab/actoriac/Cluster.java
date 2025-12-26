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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages a cluster of nodes based on an Ansible inventory file.
 *
 * <p>This class reads an Ansible inventory file and creates actor references
 * for each node in specified groups. It provides methods to execute commands
 * across groups of nodes concurrently using the actor model.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Cluster {

    private final ActorSystem actorSystem;
    private InventoryParser.Inventory inventory;
    private final Map<String, ActorRef<Node>> nodeActors = new HashMap<>();

    /**
     * Constructs a Cluster with the specified actor system.
     *
     * @param actorSystem the actor system to use for creating node actors
     */
    public Cluster(ActorSystem actorSystem) {
        this.actorSystem = actorSystem;
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
     * Creates node actors for all hosts in the specified group.
     *
     * <p>This method reads the group from the inventory, applies global and
     * group-specific variables, and creates an ActorRef&lt;Node&gt; for each host.</p>
     *
     * @param groupName the name of the group from the inventory file
     * @return the list of created node actors
     * @throws IllegalStateException if inventory has not been loaded
     */
    public List<ActorRef<Node>> createNodesForGroup(String groupName) {
        if (inventory == null) {
            throw new IllegalStateException("Inventory not loaded. Call loadInventory() first.");
        }

        List<String> hosts = inventory.getHosts(groupName);
        List<ActorRef<Node>> actors = new ArrayList<>();

        // Get base variables
        Map<String, String> globalVars = inventory.getGlobalVars();
        Map<String, String> groupVars = inventory.getGroupVars(groupName);

        // Create node actors
        for (String hostname : hosts) {
            // Merge vars with priority: host vars > group vars > global vars
            Map<String, String> effectiveVars = new HashMap<>(globalVars);
            effectiveVars.putAll(groupVars);
            effectiveVars.putAll(inventory.getHostVars(hostname));

            // Extract connection parameters for this host
            String user = effectiveVars.getOrDefault("ansible_user", System.getProperty("user.name"));
            int port = Integer.parseInt(effectiveVars.getOrDefault("ansible_port", "22"));
            String identityFile = effectiveVars.get("ansible_ssh_private_key_file");

            Node node = new Node(hostname, user, port, identityFile);
            ActorRef<Node> nodeActor = actorSystem.actorOf(
                "node-" + hostname.replace(".", "-"),
                node
            );

            nodeActors.put(hostname, nodeActor);
            actors.add(nodeActor);
        }

        return actors;
    }

    /**
     * Gets the node actor for a specific hostname.
     *
     * @param hostname the hostname of the node
     * @return the actor reference for the node, or null if not found
     */
    public ActorRef<Node> getNodeActor(String hostname) {
        return nodeActors.get(hostname);
    }

    /**
     * Gets all node actors that have been created.
     *
     * @return a map of hostname to node actor
     */
    public Map<String, ActorRef<Node>> getAllNodeActors() {
        return new HashMap<>(nodeActors);
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
     * Gets the actor system used by this cluster.
     *
     * @return the actor system
     */
    public ActorSystem getActorSystem() {
        return actorSystem;
    }

    /**
     * Gets the number of nodes currently managed by this cluster.
     *
     * @return the node count
     */
    public int getNodeCount() {
        return nodeActors.size();
    }

    @Override
    public String toString() {
        return String.format("Cluster{nodeCount=%d, groups=%s}",
            nodeActors.size(),
            inventory != null ? inventory.getAllGroups().keySet() : "[]");
    }
}
