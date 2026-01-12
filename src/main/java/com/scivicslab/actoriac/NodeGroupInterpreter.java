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

import com.github.ricksbrown.cowsay.Cowsay;
import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.Transition;

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
     * The overlay directory path for YAML overlay feature.
     * When set, workflows are loaded with overlay applied.
     */
    private String overlayDir;

    /**
     * Verbose output flag.
     * When true, displays full YAML for each step instead of truncated version.
     */
    private boolean verbose = false;

    /**
     * Distributed log store for structured logging.
     */
    private DistributedLogStore logStore;

    /**
     * Session ID for the current workflow execution.
     */
    private long sessionId = -1;

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

    /**
     * Sets the overlay directory for YAML overlay feature.
     *
     * <p>When an overlay directory is set, workflows will be loaded with
     * overlay applied, allowing environment-specific configuration.</p>
     *
     * @param overlayDir the path to the overlay directory containing overlay-conf.yaml
     */
    public void setOverlayDir(String overlayDir) {
        this.overlayDir = overlayDir;
    }

    /**
     * Gets the overlay directory path.
     *
     * @return the overlay directory path, or null if not set
     */
    public String getOverlayDir() {
        return overlayDir;
    }

    /**
     * Sets verbose mode for detailed output.
     *
     * <p>When enabled, displays full YAML for each step in cowsay output
     * instead of the truncated version.</p>
     *
     * @param verbose true to enable verbose output
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Checks if verbose mode is enabled.
     *
     * @return true if verbose mode is enabled
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Sets the distributed log store for structured logging.
     *
     * @param logStore the log store to use
     * @param sessionId the session ID for this execution
     */
    public void setLogStore(DistributedLogStore logStore, long sessionId) {
        this.logStore = logStore;
        this.sessionId = sessionId;
    }

    /**
     * Gets the log store.
     *
     * @return the log store, or null if not set
     */
    public DistributedLogStore getLogStore() {
        return logStore;
    }

    /**
     * Gets the session ID.
     *
     * @return the session ID, or -1 if not set
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * Hook called when entering a step during workflow execution.
     *
     * <p>Displays the workflow name and transition definition using cowsay.
     * In normal mode, shows first 10 lines. In verbose mode, shows the full YAML
     * after the cowsay output.</p>
     *
     * @param transition the transition being entered
     */
    @Override
    protected void onEnterTransition(Transition transition) {
        // Get workflow name
        String workflowName = (getCode() != null && getCode().getName() != null)
                ? getCode().getName()
                : "unknown-workflow";

        // Get YAML-formatted output (first 10 lines for cowsay)
        String yamlText = transition.toYamlString(10).trim();

        // Combine workflow name and transition YAML
        String displayText = "[" + workflowName + "]\n" + yamlText;
        String[] cowsayArgs = { displayText };
        System.out.println(Cowsay.say(cowsayArgs));

        // In verbose mode, show the full YAML after cowsay
        if (verbose) {
            String fullYaml = transition.toYamlString(-1);
            System.out.println("--- Full transition YAML ---");
            System.out.println(fullYaml);
            System.out.println("----------------------------");
        }

        // Log to distributed log store
        if (logStore != null && sessionId >= 0) {
            String transitionName = transition.getTransitionName();
            if (transitionName == null && transition.getStates() != null && transition.getStates().size() >= 2) {
                transitionName = transition.getStates().get(0) + " -> " + transition.getStates().get(1);
            }
            logStore.log(sessionId, "nodeGroup", transitionName, LogLevel.INFO,
                    "Entering transition: " + yamlText.split("\n")[0]);
        }
    }
}
