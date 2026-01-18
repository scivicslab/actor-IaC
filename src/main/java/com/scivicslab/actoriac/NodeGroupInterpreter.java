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
import java.util.concurrent.ExecutorService;

import org.json.JSONObject;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.workflow.IIActorRef;
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
     * When true, displays full YAML for each transition instead of truncated version.
     */
    private boolean verbose = false;

    /**
     * IaCStreamingAccumulator for displaying workflow steps with cowsay ASCII art.
     * When set, workflow step transitions are displayed using this accumulator.
     */
    private IaCStreamingAccumulator accumulator = null;

    /**
     * Actor reference for the distributed log store.
     * Used to send log messages asynchronously to avoid blocking workflow execution.
     */
    private ActorRef<DistributedLogStore> logStoreActor;

    /**
     * Direct reference to the log store for synchronous read operations.
     * Reads don't need to go through the actor since they don't need serialization.
     */
    private DistributedLogStore logStore;

    /**
     * Dedicated executor service for DB writes.
     * Should be a single-threaded pool to ensure serialized writes.
     */
    private ExecutorService dbExecutor;

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
     * <p>When enabled, displays full YAML for each transition in cowsay output
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
     * Sets the IaCStreamingAccumulator for displaying workflow steps.
     *
     * <p>When set, workflow step transitions are displayed using cowsay ASCII art
     * via this accumulator. The accumulator's cowfile setting determines which
     * character is used.</p>
     *
     * @param accumulator the accumulator to use for cowsay display
     */
    public void setAccumulator(IaCStreamingAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    /**
     * Gets the IaCStreamingAccumulator for displaying workflow steps.
     *
     * @return the cowsay accumulator, or null if not set
     */
    public IaCStreamingAccumulator getAccumulator() {
        return accumulator;
    }

    /**
     * Sets the distributed log store for structured logging.
     *
     * <p>Database writes are performed asynchronously via the logStore actor
     * using the dedicated dbExecutor to avoid blocking workflow execution.
     * Direct reads can use the logStore reference directly.</p>
     *
     * @param logStore the log store instance (for direct reads)
     * @param logStoreActor the actor reference for the log store (for async writes)
     * @param dbExecutor the dedicated executor service for DB writes
     * @param sessionId the session ID for this execution
     */
    public void setLogStore(DistributedLogStore logStore,
                            ActorRef<DistributedLogStore> logStoreActor,
                            ExecutorService dbExecutor, long sessionId) {
        this.logStore = logStore;
        this.logStoreActor = logStoreActor;
        this.dbExecutor = dbExecutor;
        this.sessionId = sessionId;
    }

    /**
     * Gets the log store for direct read operations.
     *
     * @return the log store, or null if not set
     */
    public DistributedLogStore getLogStore() {
        return logStore;
    }

    /**
     * Gets the log store actor for async write operations.
     *
     * @return the log store actor, or null if not set
     */
    public ActorRef<DistributedLogStore> getLogStoreActor() {
        return logStoreActor;
    }

    /**
     * Gets the DB executor service.
     *
     * @return the DB executor service, or null if not set
     */
    public ExecutorService getDbExecutor() {
        return dbExecutor;
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
     * Hook called when entering a transition during workflow execution.
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

        // Render cowsay output
        String cowsayOutput;
        if (accumulator != null) {
            cowsayOutput = accumulator.renderCowsay(workflowName, yamlText);
        } else {
            // Fallback to simple text if no accumulator
            cowsayOutput = "[" + workflowName + "]\n" + yamlText;
        }

        // Send cowsay output to outputMultiplexer (loose coupling via ActorSystem)
        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        // Use actor name as source (consistent across all output)
        String actorName = selfActorRef != null ? selfActorRef.getName() : "unknown";
        if (multiplexer != null) {
            JSONObject arg = new JSONObject();
            arg.put("source", actorName);
            arg.put("type", "cowsay");
            arg.put("data", cowsayOutput);
            multiplexer.callByActionName("add", arg.toString());
        }

        // In verbose mode, also send the full YAML
        if (verbose) {
            String fullYaml = transition.toYamlString(-1);
            String verboseOutput = "--- Full transition YAML ---\n" + fullYaml + "\n----------------------------";
            if (multiplexer != null) {
                JSONObject arg = new JSONObject();
                arg.put("source", actorName);
                arg.put("type", "verbose");
                arg.put("data", verboseOutput);
                multiplexer.callByActionName("add", arg.toString());
            }
        }

        // Log to distributed log store asynchronously
        if (logStoreActor != null && sessionId >= 0) {
            String label = transition.getLabel();
            if (label == null && transition.getStates() != null && transition.getStates().size() >= 2) {
                label = transition.getStates().get(0) + " -> " + transition.getStates().get(1);
            }
            final String finalLabel = label;
            final String message = "Entering transition: " + yamlText.split("\n")[0];
            // Fire-and-forget: don't wait for DB write to complete
            logStoreActor.tell(
                store -> store.log(sessionId, "nodeGroup", finalLabel, LogLevel.INFO, message),
                dbExecutor);
        }
    }

    /**
     * Hook called after a transition completes (success or failure).
     *
     * <p>Logs the transition result to the distributed log store for
     * workflow execution reporting.</p>
     *
     * @param transition the transition that was attempted
     * @param success true if the transition succeeded, false if it failed
     * @param result the ActionResult from executing the transition's actions
     */
    @Override
    protected void onExitTransition(Transition transition, boolean success, ActionResult result) {
        if (logStoreActor == null || sessionId < 0) {
            return;
        }

        String label = transition.getLabel();
        if (label == null && transition.getStates() != null && transition.getStates().size() >= 2) {
            label = transition.getStates().get(0) + " -> " + transition.getStates().get(1);
        }
        final String finalLabel = label;

        String status = success ? "SUCCESS" : "FAILED";
        String resultMsg = result != null ? result.getResult() : "";
        // Truncate long result messages
        if (resultMsg.length() > 500) {
            resultMsg = resultMsg.substring(0, 500) + "...";
        }
        final String message = "Transition " + status + ": " + finalLabel +
                (resultMsg.isEmpty() ? "" : " - " + resultMsg);

        LogLevel level = success ? LogLevel.INFO : LogLevel.WARN;

        // Fire-and-forget: don't wait for DB write to complete
        logStoreActor.tell(
            store -> store.log(sessionId, "nodeGroup", finalLabel, level, message),
            dbExecutor);
    }
}
