/*
 * Copyright 2025 devteam@scivicslab.com
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import com.scivicslab.actoriac.log.DistributedLogStore;

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
 * <p><strong>Supported actions:</strong></p>
 * <p><em>Workflow actions (from Interpreter):</em></p>
 * <ul>
 *   <li>{@code runWorkflow} - Loads and runs a workflow file</li>
 *   <li>{@code readYaml} - Reads a YAML workflow definition</li>
 *   <li>{@code runUntilEnd} - Executes the workflow until completion</li>
 * </ul>
 * <p><em>NodeGroup actions:</em></p>
 * <ul>
 *   <li>{@code hasInventory} - Returns true if inventory is loaded (for conditional branching)</li>
 *   <li>{@code createNodeActors} - Creates child actors for all nodes in a specified group</li>
 *   <li>{@code apply} - Applies an action to child actors matching a wildcard pattern</li>
 *   <li>{@code hasAccumulator} - Returns true if accumulator exists (for idempotent workflows)</li>
 *   <li>{@code createAccumulator} - Creates an accumulator for result collection</li>
 *   <li>{@code getAccumulatorSummary} - Gets the collected results</li>
 *   <li>{@code getSessionId} - Gets the current session ID for log queries</li>
 * </ul>
 *
 * <p><strong>Node Actor Hierarchy:</strong></p>
 * <p>When {@code createNodeActors} is called, it creates a parent-child relationship:</p>
 * <pre>
 * NodeGroup (parent)
 *   ├─ node-web-01 (child NodeIIAR)
 *   ├─ node-web-02 (child NodeIIAR)
 *   └─ node-db-01 (child NodeIIAR)
 * </pre>
 *
 * <p><strong>Example YAML Workflow:</strong></p>
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
 * @author devteam@scivicslab.com
 */
public class NodeGroupIIAR extends IIActorRef<NodeGroupInterpreter> {

    Logger logger = null;

    /** Current workflow file path being executed. */
    private String currentWorkflowPath = null;

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

    // ========================================================================
    // Workflow Actions (from Interpreter)
    // ========================================================================

    /**
     * Executes the current step code.
     *
     * @param args the argument string (not used)
     * @return ActionResult indicating success or failure
     */
    @Action("execCode")
    public ActionResult execCode(String args) {
        try {
            return this.ask(n -> n.execCode()).get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "execCode failed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Runs the workflow until completion.
     *
     * @param args the argument string (optional max iterations)
     * @return ActionResult indicating success or failure
     */
    @Action("runUntilEnd")
    public ActionResult runUntilEnd(String args) {
        try {
            int maxIterations = parseMaxIterations(args, 10000);
            return this.ask(n -> n.runUntilEnd(maxIterations)).get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "runUntilEnd failed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Loads and runs a workflow file.
     *
     * @param args JSON array with workflow file path and optional max iterations
     * @return ActionResult indicating success or failure
     */
    @Action("runWorkflow")
    public ActionResult runWorkflow(String args) {
        try {
            JSONArray runArgs = new JSONArray(args);
            String workflowFile = runArgs.getString(0);
            this.currentWorkflowPath = workflowFile;  // Store for WorkflowReporter
            int runMaxIterations = runArgs.length() > 1 ? runArgs.getInt(1) : 10000;
            logger.info(String.format("Running workflow: %s (maxIterations=%d)", workflowFile, runMaxIterations));
            ActionResult result = this.object.runWorkflow(workflowFile, runMaxIterations);
            logger.info(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "runWorkflow failed: " + args, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Reads a YAML workflow definition.
     *
     * @param args JSON array with file path
     * @return ActionResult indicating success or failure
     */
    @Action("readYaml")
    public ActionResult readYaml(String args) {
        String filePath = extractSingleArgument(args);
        this.currentWorkflowPath = filePath;  // Store for WorkflowReporter
        try {
            String overlayPath = this.object.getOverlayDir();
            if (overlayPath != null) {
                java.nio.file.Path yamlPath = java.nio.file.Path.of(filePath);
                java.nio.file.Path overlayDir = java.nio.file.Path.of(overlayPath);
                this.tell(n -> {
                    try {
                        n.readYaml(yamlPath, overlayDir);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();
                return new ActionResult(true, "YAML loaded with overlay: " + overlayPath);
            } else {
                try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(filePath))) {
                    this.tell(n -> n.readYaml(input)).get();
                    return new ActionResult(true, "YAML loaded successfully");
                }
            }
        } catch (java.io.FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", filePath), e);
            return new ActionResult(false, "File not found: " + filePath);
        } catch (java.io.IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", filePath), e);
            return new ActionResult(false, "IO error: " + filePath);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof java.io.IOException) {
                logger.log(Level.SEVERE, String.format("IOException: %s", filePath), e.getCause());
                return new ActionResult(false, "IO error: " + filePath);
            }
            throw e;
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "readYaml failed: " + filePath, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    // ========================================================================
    // NodeGroup-specific Actions
    // ========================================================================

    /**
     * Checks if inventory is loaded.
     *
     * @param args the argument string (not used)
     * @return ActionResult with true if inventory exists
     */
    @Action("hasInventory")
    public ActionResult hasInventory(String args) {
        boolean hasInv = this.object.getInventory() != null;
        return new ActionResult(hasInv, hasInv ? "Inventory available" : "No inventory");
    }

    /**
     * Creates child actors for all nodes in a specified group.
     *
     * @param args JSON array with group name
     * @return ActionResult indicating success or failure
     */
    @Action("createNodeActors")
    public ActionResult createNodeActorsAction(String args) {
        String groupName = extractSingleArgument(args);
        createNodeActors(groupName);
        return new ActionResult(true, String.format("Created node actors for group '%s'", groupName));
    }

    /**
     * Applies an action to child actors matching a wildcard pattern.
     *
     * @param args JSON object defining the action to apply
     * @return ActionResult indicating success or failure
     */
    @Action("apply")
    public ActionResult applyAction(String args) {
        return apply(args);
    }

    /**
     * Executes a command on all child node actors.
     *
     * @param args JSON array with command
     * @return ActionResult with execution results
     */
    @Action("executeCommandOnAllNodes")
    public ActionResult executeCommandOnAllNodesAction(String args) {
        try {
            String command = extractSingleArgument(args);
            List<String> results = executeCommandOnAllNodes(command);
            return new ActionResult(true,
                String.format("Executed command on %d nodes: %s", results.size(), results));
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "executeCommandOnAllNodes failed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Checks if accumulator exists.
     *
     * @param args the argument string (not used)
     * @return ActionResult with true if accumulator exists
     */
    @Action("hasAccumulator")
    public ActionResult hasAccumulator(String args) {
        boolean hasAcc = ((IIActorSystem) this.system()).getIIActor("outputMultiplexer") != null;
        return new ActionResult(hasAcc, hasAcc ? "Accumulator exists" : "No accumulator");
    }

    /**
     * Creates an accumulator (no-op, kept for backward compatibility).
     *
     * @param args the argument string (not used)
     * @return ActionResult with success
     */
    @Action("createAccumulator")
    public ActionResult createAccumulator(String args) {
        // No-op: MultiplexerAccumulator is now created by RunCLI
        // This case is kept for backward compatibility with existing workflows
        return new ActionResult(true, "Accumulator managed by CLI");
    }

    /**
     * Gets the summary from the output multiplexer.
     *
     * @param args the argument string (not used)
     * @return ActionResult with the summary
     */
    @Action("getAccumulatorSummary")
    public ActionResult getAccumulatorSummaryAction(String args) {
        return getAccumulatorSummary();
    }

    /**
     * Prints a summary of the current session's verification results.
     *
     * @param args the argument string (not used)
     * @return ActionResult with the summary
     */
    @Action("printSessionSummary")
    public ActionResult printSessionSummaryAction(String args) {
        return printSessionSummary();
    }

    /**
     * Gets the current session ID.
     *
     * @param args the argument string (not used)
     * @return ActionResult with the session ID
     */
    @Action("getSessionId")
    public ActionResult getSessionId(String args) {
        long sessionId = this.object.getSessionId();
        if (sessionId < 0) {
            return new ActionResult(false, "No session ID set");
        }
        return new ActionResult(true, String.valueOf(sessionId));
    }

    /**
     * Gets the current workflow file path.
     *
     * @param args the argument string (not used)
     * @return ActionResult with the workflow path
     */
    @Action("getWorkflowPath")
    public ActionResult getWorkflowPath(String args) {
        if (currentWorkflowPath == null) {
            return new ActionResult(false, "No workflow path set");
        }
        return new ActionResult(true, currentWorkflowPath);
    }

    /**
     * Does nothing, returns the argument as result.
     *
     * @param args the argument string
     * @return ActionResult with the argument
     */
    @Action("doNothing")
    public ActionResult doNothing(String args) {
        return new ActionResult(true, args);
    }

    // ========================================================================
    // JSON State Output Actions
    // ========================================================================

    /**
     * Outputs JSON State at the given path in pretty JSON format via outputMultiplexer.
     *
     * @param args the path to output (from JSON array)
     * @return ActionResult with the formatted JSON
     */
    @Action("printJson")
    public ActionResult printJson(String args) {
        String path = getFirst(args);
        String formatted = toStringOfJson(path);
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /**
     * Outputs JSON State at the given path in YAML format via outputMultiplexer.
     *
     * @param args the path to output (from JSON array)
     * @return ActionResult with the formatted YAML
     */
    @Action("printYaml")
    public ActionResult printYaml(String args) {
        String path = getFirst(args);
        String formatted = toStringOfYaml(path);
        sendToMultiplexer(formatted);
        return new ActionResult(true, formatted);
    }

    /**
     * Sends formatted output to the outputMultiplexer, line by line.
     */
    private void sendToMultiplexer(String formatted) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");
        if (multiplexer == null) {
            return;
        }

        String nodeName = this.getName();
        for (String line : formatted.split("\n")) {
            JSONObject arg = new JSONObject();
            arg.put("source", nodeName);
            arg.put("type", "stdout");
            arg.put("data", line);
            multiplexer.callByActionName("add", arg.toString());
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private int parseMaxIterations(String arg, int defaultValue) {
        if (arg != null && !arg.isEmpty() && !arg.equals("[]")) {
            try {
                JSONArray args = new JSONArray(arg);
                if (args.length() > 0) {
                    return args.getInt(0);
                }
            } catch (Exception e) {
                // Use default if parsing fails
            }
        }
        return defaultValue;
    }

    private String getFirst(String args) {
        if (args == null || args.isEmpty() || args.equals("[]")) {
            return "";
        }
        try {
            JSONArray jsonArray = new JSONArray(args);
            if (jsonArray.length() > 0) {
                return jsonArray.getString(0);
            }
        } catch (Exception e) {
            // Return empty string if parsing fails
        }
        return "";
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

            // Propagate overlayDir to child interpreter
            if (nodeGroupInterpreter.getOverlayDir() != null) {
                nodeInterpreter.setOverlayDir(nodeGroupInterpreter.getOverlayDir());
            }

            // Propagate accumulator to child interpreter
            if (nodeGroupInterpreter.getAccumulator() != null) {
                nodeInterpreter.setAccumulator(nodeGroupInterpreter.getAccumulator());
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
    /**
     * Applies an action to multiple actors matching a pattern in parallel.
     *
     * <p>This method executes the specified action on all matching actors
     * asynchronously and in parallel, then waits for all executions to complete
     * before returning. This provides significant performance benefits when
     * executing actions on many nodes.</p>
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

            logger.info(String.format("Applying method '%s' to %d actors matching '%s' (async parallel)",
                method, matchedActors.size(), actorPattern));

            // Thread-safe collections for gathering results
            AtomicInteger successCount = new AtomicInteger(0);
            Map<String, String> failures = new ConcurrentHashMap<>();
            DistributedLogStore logStore = this.object.getLogStore();
            long sessionId = this.object.getSessionId();

            // Create async tasks for all actors
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (IIActorRef<?> actor : matchedActors) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        ActionResult result = actor.callByActionName(method, args);
                        if (!result.isSuccess()) {
                            failures.put(actor.getName(), result.getResult());
                            logger.warning(String.format("Failed on %s: %s", actor.getName(), result.getResult()));
                            // Record node failure in log store
                            if (logStore != null && sessionId >= 0) {
                                logStore.markNodeFailed(sessionId, actor.getName(), result.getResult());
                            }
                        } else {
                            successCount.incrementAndGet();
                            logger.fine(String.format("Applied to %s: %s", actor.getName(), result.getResult()));
                            // Record node success in log store
                            if (logStore != null && sessionId >= 0) {
                                logStore.markNodeSuccess(sessionId, actor.getName());
                            }
                        }
                    } catch (Exception e) {
                        failures.put(actor.getName(), e.getMessage());
                        logger.log(Level.WARNING, "Exception on " + actor.getName(), e);
                    }
                });
                futures.add(future);
            }

            // Wait for all async tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (failures.isEmpty()) {
                return new ActionResult(true,
                    String.format("Applied to %d actors", successCount.get()));
            } else {
                List<String> failureMessages = new ArrayList<>();
                failures.forEach((name, msg) -> failureMessages.add(name + ": " + msg));
                return new ActionResult(false,
                    String.format("Applied to %d/%d actors. Failures: %s",
                        successCount.get(), matchedActors.size(), String.join("; ", failureMessages)));
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
     * Gets the summary from the output multiplexer.
     *
     * <p>Retrieves the multiplexer actor from ActorSystem by name ("outputMultiplexer")
     * and calls its getSummary action.</p>
     *
     * @return ActionResult with the summary or error
     */
    private ActionResult getAccumulatorSummary() {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");
        if (multiplexer == null) {
            return new ActionResult(false, "No output multiplexer registered");
        }
        ActionResult result = multiplexer.callByActionName("getSummary", "");
        return result;
    }

    /**
     * Prints a summary of the current session's verification results.
     *
     * <p>Groups results by label (step) and displays a formatted table.</p>
     *
     * @return ActionResult with success status and summary text
     */
    private ActionResult printSessionSummary() {
        DistributedLogStore logStore = this.object.getLogStore();
        long sessionId = this.object.getSessionId();

        if (logStore == null || sessionId < 0) {
            String msg = "Log store not available";
            System.out.println(msg);
            return new ActionResult(false, msg);
        }

        // Get all logs for this session
        List<com.scivicslab.actoriac.log.LogEntry> logs = logStore.getLogsByLevel(sessionId,
            com.scivicslab.actoriac.log.LogLevel.DEBUG);

        // Group logs by label and count results
        java.util.Map<String, VerifyResult> resultsByLabel = new java.util.LinkedHashMap<>();

        for (com.scivicslab.actoriac.log.LogEntry entry : logs) {
            String message = entry.getMessage();
            String label = entry.getLabel();
            if (message == null) continue;

            // Extract label from the message if it contains step info
            // Format: "- states: [...]\n  label: xxx\n..."
            if (label != null && label.contains("label:")) {
                int idx = label.indexOf("label:");
                if (idx >= 0) {
                    String rest = label.substring(idx + 11).trim();
                    int end = rest.indexOf('\n');
                    label = end > 0 ? rest.substring(0, end).trim() : rest.trim();
                }
            }

            // Skip non-verify steps
            if (label == null || !label.startsWith("verify-")) {
                continue;
            }

            VerifyResult result = resultsByLabel.computeIfAbsent(label, k -> new VerifyResult());

            // Count occurrences in message
            result.okCount += countOccurrences(message, "[OK]");
            result.warnCount += countOccurrences(message, "[WARN]");
            result.errorCount += countOccurrences(message, "[ERROR]");
            result.infoCount += countOccurrences(message, "[INFO]");

            // Extract special info (like document count, cluster health)
            extractSpecialInfo(message, result);
        }

        // Build summary output
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("============================================================\n");
        sb.append("                 VERIFICATION SUMMARY\n");
        sb.append("============================================================\n");
        sb.append("\n");
        sb.append(String.format("| %-35s | %-20s |\n", "Item", "Status"));
        sb.append("|-------------------------------------|----------------------|\n");

        // Mapping from labels to display names and aggregation
        String[][] mappings = {
            {"verify-repos", "Document repositories"},
            {"verify-utility-cli", "Utility-cli"},
            {"verify-utility-sau3", "Utility-sau3"},
            {"verify-builds", "Docusaurus builds"},
            {"verify-public-html", "public_html deploy"},
            {"verify-apache", "Apache2 + UserDir"},
            {"verify-opensearch-install", "OpenSearch install"},
            {"verify-opensearch-running", "OpenSearch status"},
            {"verify-docusearch-build", "quarkus-docusearch build"},
            {"verify-docusearch-running", "quarkus-docusearch server"},
            {"verify-search-index", "Search index"},
            {"verify-web-access", "Web access"},
        };

        int totalOk = 0, totalWarn = 0, totalError = 0;
        int executedChecks = 0;
        List<String> errorDetails = new ArrayList<>();
        List<String> warnDetails = new ArrayList<>();

        for (String[] mapping : mappings) {
            String label = mapping[0];
            String displayName = mapping[1];
            VerifyResult result = resultsByLabel.get(label);

            if (result == null) {
                sb.append(String.format("| %-35s | %-20s |\n", displayName, "SKIP"));
                continue;
            }
            executedChecks++;

            totalOk += result.okCount;
            totalWarn += result.warnCount;
            totalError += result.errorCount;

            String status = formatStatus(result);
            sb.append(String.format("| %-35s | %-20s |\n", displayName, status));

            // Collect error/warning details
            if (result.errorCount > 0) {
                errorDetails.add(displayName + ": " + result.errorCount + " error(s)");
            }
            if (result.warnCount > 0) {
                warnDetails.add(displayName + ": " + result.warnCount + " warning(s)");
            }
        }

        sb.append("|-------------------------------------|----------------------|\n");
        sb.append(String.format("| %-35s | %d OK, %d WARN, %d ERR |\n",
            "TOTAL", totalOk, totalWarn, totalError));
        sb.append("============================================================\n");

        // Show error details if any
        if (!errorDetails.isEmpty()) {
            sb.append("\n--- Errors ---\n");
            for (String detail : errorDetails) {
                sb.append("  * ").append(detail).append("\n");
            }
        }

        // Show warning details if any
        if (!warnDetails.isEmpty()) {
            sb.append("\n--- Warnings ---\n");
            for (String detail : warnDetails) {
                sb.append("  * ").append(detail).append("\n");
            }
        }

        sb.append("\n");
        if (executedChecks == 0) {
            sb.append("No verification checks were executed.\n");
        } else if (totalError == 0 && totalWarn == 0) {
            sb.append("All checks passed!\n");
        } else if (totalError > 0) {
            sb.append("To fix issues, run:\n");
            sb.append("  ./actor_iac.java --dir ./docu-search --workflow main-setup\n");
        }

        String summary = sb.toString();
        System.out.println(summary);
        return new ActionResult(true, summary);
    }

    /**
     * Formats the status string for a verification result.
     */
    private String formatStatus(VerifyResult result) {
        if (result.errorCount > 0) {
            if (result.okCount > 0) {
                return String.format("%d OK, %d ERROR", result.okCount, result.errorCount);
            }
            return "ERROR";
        }
        if (result.warnCount > 0) {
            if (result.okCount > 0) {
                return String.format("%d OK, %d WARN", result.okCount, result.warnCount);
            }
            return "WARN";
        }
        if (result.okCount > 0) {
            String extra = result.extraInfo != null ? " " + result.extraInfo : "";
            return result.okCount + " OK" + extra;
        }
        return "OK";
    }

    /**
     * Extracts special information from log messages (like document count, cluster health).
     */
    private void extractSpecialInfo(String message, VerifyResult result) {
        // Extract document count from search index
        if (message.contains("documents")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+documents").matcher(message);
            if (m.find()) {
                result.extraInfo = "(" + m.group(1) + " docs)";
            }
        }
        // Extract cluster health
        if (message.contains("Cluster health:")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Cluster health:\\s*(\\w+)").matcher(message);
            if (m.find()) {
                result.extraInfo = "(" + m.group(1) + ")";
            }
        }
        // Extract web access count
        if (message.contains("Accessible at")) {
            // Count from "X / Y" pattern is handled by OK count
        }
    }

    /**
     * Counts occurrences of a substring in a string.
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Helper class to hold verification results for a step.
     */
    private static class VerifyResult {
        int okCount = 0;
        int warnCount = 0;
        int errorCount = 0;
        int infoCount = 0;
        String extraInfo = null;
    }

}
