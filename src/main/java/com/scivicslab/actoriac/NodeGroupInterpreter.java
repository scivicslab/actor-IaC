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

import java.util.List;

import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;

/**
 * Level 3 wrapper that adds workflow capabilities to a NodeGroup POJO.
 *
 * <p>This class extends {@link Interpreter} to provide workflow execution
 * capabilities while delegating node group operations to a wrapped {@link NodeGroup} instance.</p>
 *
 * <p>This follows the same three-level architecture as NodeInterpreter:</p>
 * <ul>
 * <li><strong>Level 1 (POJO):</strong> {@link NodeGroup} - pure POJO with inventory management</li>
 * <li><strong>Level 2 (Actor):</strong> ActorRef&lt;NodeGroup&gt; - actor wrapper for concurrent execution</li>
 * <li><strong>Level 3 (Workflow):</strong> NodeGroupInterpreter - workflow capabilities + IIActorRef wrapper</li>
 * </ul>
 *
 * <p><strong>Design principle:</strong> NodeGroup remains a pure POJO, independent of ActorSystem.
 * NodeGroupInterpreter wraps NodeGroup to add workflow capabilities without modifying the NodeGroup class.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeGroupInterpreter extends Interpreter {

    /**
     * The wrapped NodeGroup POJO that handles inventory and node creation.
     */
    private final NodeGroup nodeGroup;

    /**
     * Constructs a NodeGroupInterpreter that wraps the specified NodeGroup.
     *
     * @param nodeGroup the {@link NodeGroup} instance to wrap
     * @param system the actor system for workflow execution
     */
    public NodeGroupInterpreter(NodeGroup nodeGroup, IIActorSystem system) {
        super();
        this.nodeGroup = nodeGroup;
        this.system = system;
    }

    /**
     * Creates Node objects for all hosts in the specified group.
     *
     * <p>Delegates to the wrapped {@link NodeGroup#createNodesForGroup(String)} method.</p>
     *
     * @param groupName the name of the group from the inventory file
     * @return the list of created Node objects
     */
    public List<Node> createNodesForGroup(String groupName) {
        return nodeGroup.createNodesForGroup(groupName);
    }

    /**
     * Creates a single Node for localhost execution.
     *
     * <p>Delegates to the wrapped {@link NodeGroup#createLocalNode()} method.</p>
     *
     * @return a list containing a single localhost Node
     */
    public List<Node> createLocalNode() {
        return nodeGroup.createLocalNode();
    }

    /**
     * Gets the inventory object.
     *
     * @return the loaded inventory, or null if not loaded
     */
    public InventoryParser.Inventory getInventory() {
        return nodeGroup.getInventory();
    }

    /**
     * Gets the wrapped NodeGroup instance.
     *
     * <p>This allows direct access to the underlying POJO when needed.</p>
     *
     * @return the wrapped NodeGroup
     */
    public NodeGroup getNodeGroup() {
        return nodeGroup;
    }
}
