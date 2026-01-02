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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.pojoactor.core.accumulator.BufferedAccumulator;
import com.scivicslab.pojoactor.core.accumulator.JsonAccumulator;
import com.scivicslab.pojoactor.core.accumulator.StreamingAccumulator;
import com.scivicslab.pojoactor.core.accumulator.TableAccumulator;
import com.scivicslab.pojoactor.workflow.accumulator.AccumulatorIIAR;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for {@link NodeGroupInterpreter} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link NodeGroupInterpreter} objects. It manages groups of infrastructure
 * nodes and can apply actions to all nodes in a group using wildcard patterns.</p>
 *
 * <p>NodeGroupInterpreter extends Interpreter, so this class can execute main workflows
 * that orchestrate multiple nodes.</p>
 *
 * <p>Supported actions include:</p>
 * <ul>
 * <li><strong>Workflow actions (from Interpreter):</strong></li>
 *   <ul>
 *   <li>{@code runWorkflow} - Loads and runs a workflow file</li>
 *   <li>{@code readYaml} - Reads a YAML workflow definition</li>
 *   <li>{@code runUntilEnd} - Executes the workflow until completion</li>
 *   </ul>
 * <li><strong>NodeGroup actions:</strong></li>
 *   <ul>
 *   <li>{@code hasInventory} - Returns true if inventory is loaded (for conditional branching)</li>
 *   <li>{@code createNodeActors} - Creates child actors for all nodes in a specified group</li>
 *   <li>{@code apply} - Applies an action to child actors matching a wildcard pattern</li>
 *   <li>{@code hasAccumulator} - Returns true if accumulator exists (for idempotent workflows)</li>
 *   <li>{@code createAccumulator} - Creates an accumulator for result collection</li>
 *   <li>{@code getAccumulatorSummary} - Gets the collected results</li>
 *   </ul>
 * </ul>
 *
 * <h3>Node Actor Hierarchy:</h3>
 * <p>When {@code createNodeActors} is called, it creates a parent-child relationship:</p>
 * <pre>
 * NodeGroup (parent)
 *   ├─ node-web-01 (child NodeIIAR)
 *   ├─ node-web-02 (child NodeIIAR)
 *   └─ node-db-01 (child NodeIIAR)
 * </pre>
 *
 * <h3>Example YAML Workflow:</h3>
 * <pre>{@code
 * name: setup-nodegroup
 * steps:
 *   # Step 1: Create node actors
 *   - states: [0, 1]
 *     actions:
 *       - actor: nodeGroup
 *         method: createNodeActors
 *         arguments: ["web-servers"]
 *
 *   # Step 2: Run workflow on all nodes (load and execute in one step)
 *   - states: [1, end]
 *     actions:
 *       - actor: nodeGroup
 *         method: apply
 *         arguments: ['{"actor": "node-*", "method": "runWorkflow", "arguments": ["deploy.yaml"]}']
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeGroupIIAR extends IIActorRef<NodeGroupInterpreter> {

    Logger logger = null;
    private AccumulatorIIAR accumulatorActor = null;

    /**
     * Constructs a new NodeGroupIIAR with the specified actor name and nodeGroupInterpreter object.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeGroupInterpreter} instance managed by this actor reference
     */
    public NodeGroupIIAR(String actorName, NodeGroupInterpreter object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Constructs a new NodeGroupIIAR with the specified actor name, nodeGroupInterpreter object,
     * and actor system.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeGroupInterpreter} instance managed by this actor reference
     * @param system the actor system managing this actor
     */
    public NodeGroupIIAR(String actorName, NodeGroupInterpreter object, IIActorSystem system) {
        super(actorName, object, system);
        logger = Logger.getLogger(actorName);

        // Set the selfActorRef in the Interpreter (NodeGroupInterpreter extends Interpreter)
        object.setSelfActorRef(this);
    }

    /**
     * Invokes an action on the node group by name with the given arguments.
     *
     * @param actionName the name of the action to execute
     * @param arg the argument string (JSON array format)
     * @return an {@link ActionResult} indicating success or failure with a message
     */
    @Override
    public ActionResult callByActionName(String actionName, String arg) {

        logger.fine(String.format("actionName = %s, args = %s", actionName, arg));

        boolean success = false;
        String message = "";

        try {
            // Workflow execution actions (from Interpreter)
            if (actionName.equals("execCode")) {
                ActionResult result = this.ask(n -> n.execCode()).get();
                return result;
            }
            else if (actionName.equals("runUntilEnd")) {
                // Parse optional maxIterations argument
                int maxIterations = 10000;
                if (arg != null && !arg.isEmpty() && !arg.equals("[]")) {
                    try {
                        JSONArray args = new JSONArray(arg);
                        if (args.length() > 0) {
                            maxIterations = args.getInt(0);
                        }
                    } catch (Exception e) {
                        // Use default if parsing fails
                    }
                }
                final int iterations = maxIterations;
                ActionResult result = this.ask(n -> n.runUntilEnd(iterations)).get();
                return result;
            }
            else if (actionName.equals("runWorkflow")) {
                // Load and run workflow directly
                JSONArray args = new JSONArray(arg);
                String workflowFile = args.getString(0);
                int maxIterations = args.length() > 1 ? args.getInt(1) : 10000;
                logger.info(String.format("Running workflow: %s (maxIterations=%d)", workflowFile, maxIterations));
                ActionResult result = this.object.runWorkflow(workflowFile, maxIterations);
                logger.info(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
                return result;
            }
            else if (actionName.equals("readYaml")) {
                String filePath = extractSingleArgument(arg);
                try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(filePath))) {
                    this.tell(n -> n.readYaml(input)).get();
                    success = true;
                    message = "YAML loaded successfully";
                } catch (java.io.FileNotFoundException e) {
                    logger.log(Level.SEVERE, String.format("file not found: %s", filePath), e);
                    message = "File not found: " + filePath;
                } catch (java.io.IOException e) {
                    logger.log(Level.SEVERE, String.format("IOException: %s", filePath), e);
                    message = "IO error: " + filePath;
                }
            }
            // NodeGroup-specific actions
            else if (actionName.equals("hasInventory")) {
                // Returns true if inventory is loaded, false otherwise
                // Used for conditional branching in workflows
                boolean hasInv = this.object.getInventory() != null;
                return new ActionResult(hasInv, hasInv ? "Inventory available" : "No inventory");
            }
            else if (actionName.equals("createNodeActors")) {
                String groupName = extractSingleArgument(arg);
                createNodeActors(groupName);
                success = true;
                message = String.format("Created node actors for group '%s'", groupName);
            }
            else if (actionName.equals("apply")) {
                // Apply action to child actors matching wildcard pattern
                ActionResult result = apply(arg);
                return result;
            }
            else if (actionName.equals("executeCommandOnAllNodes")) {
                String command = extractSingleArgument(arg);
                List<String> results = executeCommandOnAllNodes(command);
                success = true;
                message = String.format("Executed command on %d nodes: %s", results.size(), results);
            }
            else if (actionName.equals("hasAccumulator")) {
                // Returns true if accumulator already exists, false otherwise
                // Used for idempotent workflow: skip creation if already exists
                boolean hasAcc = accumulatorActor != null;
                return new ActionResult(hasAcc, hasAcc ? "Accumulator exists" : "No accumulator");
            }
            else if (actionName.equals("createAccumulator")) {
                String type = extractSingleArgument(arg);
                createAccumulator(type);
                success = true;
                message = String.format("Created %s accumulator", type);
            }
            else if (actionName.equals("getAccumulatorSummary")) {
                return getAccumulatorSummary();
            }
            else if (actionName.equals("doNothing")) {
                success = true;
                message = arg;
            }
            else {
                logger.log(Level.SEVERE, String.format("Unknown action: actorName = %s, action = %s, arg = %s",
                        this.getName(), actionName, arg));
                message = "Unknown action: " + actionName;
            }
        }
        catch (InterruptedException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            message = "Interrupted: " + e.getMessage();
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            message = "Execution error: " + e.getMessage();
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            message = "Error: " + e.getMessage();
        }

        return new ActionResult(success, message);
    }

    /**
     * Creates child node actors for all nodes in the specified group.
     *
     * <p>This method creates Node POJOs using the NodeGroup's inventory,
     * wraps each in a NodeInterpreter (for workflow capabilities),
     * then wraps in a NodeIIAR, and registers them as children of this actor
     * using the parent-child relationship mechanism.</p>
     *
     * <p>Special handling for "local" group: creates a localhost node without
     * requiring an inventory file. This is useful for development and testing.</p>
     *
     * @param groupName the name of the group from the inventory file, or "local" for localhost
     */
    private void createNodeActors(String groupName) {
        // Direct execution (no tell().get() to avoid deadlock when called from workflow)
        IIActorSystem sys = (IIActorSystem) this.system();
        NodeGroupInterpreter nodeGroupInterpreter = this.object;

        // Create Node POJOs for the group
        // Special handling for "local" group: create localhost node without inventory
        List<Node> nodes;
        if ("local".equals(groupName)) {
            nodes = nodeGroupInterpreter.createLocalNode();
        } else {
            nodes = nodeGroupInterpreter.createNodesForGroup(groupName);
        }

        // Create child actors for each node
        for (Node node : nodes) {
            String nodeName = "node-" + node.getHostname();

            // Wrap Node in NodeInterpreter to add workflow capabilities
            NodeInterpreter nodeInterpreter = new NodeInterpreter(node, sys);

            // Propagate workflowBaseDir to child interpreter
            if (nodeGroupInterpreter.getWorkflowBaseDir() != null) {
                nodeInterpreter.setWorkflowBaseDir(nodeGroupInterpreter.getWorkflowBaseDir());
            }

            // Create child actor using ActorRef.createChild()
            // This establishes parent-child relationship
            this.createChild(nodeName, nodeInterpreter);

            // Also wrap in NodeIIAR and add to system for workflow execution
            NodeIIAR nodeActor = new NodeIIAR(nodeName, nodeInterpreter, sys);
            sys.addIIActor(nodeActor);

            logger.fine(String.format("Created child node actor: %s", nodeName));
        }
        logger.info(String.format("Created %d node actors for group '%s'", nodes.size(), groupName));
    }

    /**
     * Applies an action to child actors matching a wildcard pattern.
     *
     * <p>This method parses an action definition JSON and executes the specified
     * method on all child actors whose names match the pattern.</p>
     *
     * <p>Action definition format:</p>
     * <pre>{@code
     * {
     *   "actor": "node-*",           // Wildcard pattern for actor names
     *   "method": "executeCommand",  // Method to call
     *   "arguments": ["ls -la"]      // Arguments (optional)
     * }
     * }</pre>
     *
     * <p>Supported wildcard patterns:</p>
     * <ul>
     * <li>{@code *} - Matches all child actors</li>
     * <li>{@code node-*} - Matches actors starting with "node-"</li>
     * <li>{@code *-web} - Matches actors ending with "-web"</li>
     * </ul>
     *
     * @param actionDef JSON string defining the action to apply
     * @return ActionResult indicating success or failure
     */
    private ActionResult apply(String actionDef) {
        try {
            JSONObject action = new JSONObject(actionDef);
            String actorPattern = action.getString("actor");
            String method = action.getString("method");
            JSONArray argsArray = action.optJSONArray("arguments");
            String args = argsArray != null ? argsArray.toString() : "[]";

            // Find matching child actors
            List<IIActorRef<?>> matchedActors = findMatchingChildActors(actorPattern);

            if (matchedActors.isEmpty()) {
                return new ActionResult(false, "No actors matched pattern: " + actorPattern);
            }

            logger.info(String.format("Applying method '%s' to %d actors matching '%s'",
                method, matchedActors.size(), actorPattern));

            // Apply action to each matching actor (continue on failure, report all errors)
            int successCount = 0;
            List<String> failures = new ArrayList<>();
            for (IIActorRef<?> actor : matchedActors) {
                ActionResult result = actor.callByActionName(method, args);
                if (!result.isSuccess()) {
                    failures.add(String.format("%s: %s", actor.getName(), result.getResult()));
                    logger.warning(String.format("Failed on %s: %s", actor.getName(), result.getResult()));
                } else {
                    successCount++;
                    logger.fine(String.format("Applied to %s: %s", actor.getName(), result.getResult()));
                }
            }

            if (failures.isEmpty()) {
                return new ActionResult(true,
                    String.format("Applied to %d actors", successCount));
            } else {
                return new ActionResult(false,
                    String.format("Applied to %d/%d actors. Failures: %s",
                        successCount, matchedActors.size(), String.join("; ", failures)));
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in apply: " + actionDef, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Finds child actors matching a wildcard pattern.
     *
     * @param pattern the wildcard pattern (e.g., "node-*", "*-web", "*")
     * @return list of matching child actors
     */
    private List<IIActorRef<?>> findMatchingChildActors(String pattern) {
        List<IIActorRef<?>> matched = new ArrayList<>();
        IIActorSystem system = (IIActorSystem) this.system();

        if (system == null) {
            return matched;
        }

        List<String> childNames = new ArrayList<>(this.getNamesOfChildren());

        // Exact match (no wildcard)
        if (!pattern.contains("*")) {
            if (childNames.contains(pattern)) {
                IIActorRef<?> actor = system.getIIActor(pattern);
                if (actor != null) {
                    matched.add(actor);
                }
            }
            return matched;
        }

        // Convert wildcard to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);

        for (String childName : childNames) {
            if (compiled.matcher(childName).matches()) {
                IIActorRef<?> actor = system.getIIActor(childName);
                if (actor != null) {
                    matched.add(actor);
                }
            }
        }

        return matched;
    }

    /**
     * Executes a single command on all child node actors.
     *
     * @param command the command to execute
     * @return list of results from each node
     * @throws ExecutionException if command execution fails
     * @throws InterruptedException if the operation is interrupted
     */
    private List<String> executeCommandOnAllNodes(String command)
            throws ExecutionException, InterruptedException {

        IIActorSystem system = (IIActorSystem) this.system();
        List<String> results = new ArrayList<>();

        // Get all child node names
        List<String> childNames = new ArrayList<>(this.getNamesOfChildren());

        logger.info(String.format("Executing command on %d nodes: %s", childNames.size(), command));

        // Execute on each child node
        for (String childName : childNames) {
            IIActorRef<?> actorRef = system.getIIActor(childName);
            if (actorRef == null || !(actorRef instanceof NodeIIAR)) {
                logger.warning(String.format("Child node actor not found or wrong type: %s", childName));
                continue;
            }
            NodeIIAR nodeActor = (NodeIIAR) actorRef;

            // Execute the command
            JSONArray commandArgs = new JSONArray();
            commandArgs.put(command);
            ActionResult result = nodeActor.callByActionName("executeCommand", commandArgs.toString());

            results.add(String.format("%s: %s", childName, result.getResult()));
        }

        return results;
    }

    /**
     * Extracts a single argument from JSON array format.
     *
     * @param arg the JSON array argument string
     * @return the extracted argument
     */
    private String extractSingleArgument(String arg) {
        try {
            JSONArray jsonArray = new JSONArray(arg);
            if (jsonArray.length() == 0) {
                throw new IllegalArgumentException("Arguments cannot be empty");
            }
            return jsonArray.getString(0);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid argument format. Expected JSON array: " + arg, e);
        }
    }

    /**
     * Creates an accumulator as a child actor.
     *
     * @param type the accumulator type ("streaming", "buffered", "table", "json")
     */
    private void createAccumulator(String type) {
        IIActorSystem sys = (IIActorSystem) this.system();

        // Create POJO based on type
        Accumulator accumulator;
        switch (type.toLowerCase()) {
            case "streaming":
                accumulator = new StreamingAccumulator();
                break;
            case "buffered":
                accumulator = new BufferedAccumulator();
                break;
            case "table":
                accumulator = new TableAccumulator();
                break;
            case "json":
                accumulator = new JsonAccumulator();
                break;
            default:
                throw new IllegalArgumentException("Unknown accumulator type: " + type);
        }

        // Create actor and register as child
        accumulatorActor = new AccumulatorIIAR("accumulator", accumulator, sys);
        this.createChild("accumulator", accumulator);
        sys.addIIActor(accumulatorActor);

        logger.info(String.format("Created %s accumulator as child actor", type));
    }

    /**
     * Gets the summary from the accumulator.
     *
     * @return ActionResult with the summary or error
     */
    private ActionResult getAccumulatorSummary() {
        if (accumulatorActor == null) {
            return new ActionResult(false, "No accumulator created");
        }
        ActionResult result = accumulatorActor.callByActionName("getSummary", "");
        // Print the summary to stdout
        System.out.println(result.getResult());
        return result;
    }

}
