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
 * @author devteam@scivics-lab.com
 */
public class NodeGroupIIAR extends IIActorRef<NodeGroupInterpreter> {

    Logger logger = null;

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
     * <p>This method dispatches to specialized handler methods based on the action type:</p>
     * <ul>
     *   <li>Workflow actions: {@link #handleWorkflowAction}</li>
     *   <li>NodeGroup-specific actions: {@link #handleNodeGroupAction}</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param arg the argument string (JSON array format)
     * @return an {@link ActionResult} indicating success or failure with a message
     */
    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        logger.fine(String.format("actionName = %s, args = %s", actionName, arg));

        try {
            // Workflow execution actions (from Interpreter)
            ActionResult workflowResult = handleWorkflowAction(actionName, arg);
            if (workflowResult != null) {
                return workflowResult;
            }

            // NodeGroup-specific actions
            ActionResult nodeGroupResult = handleNodeGroupAction(actionName, arg);
            if (nodeGroupResult != null) {
                return nodeGroupResult;
            }

            // Unknown action
            logger.log(Level.SEVERE, String.format("Unknown action: actorName = %s, action = %s, arg = %s",
                    this.getName(), actionName, arg));
            return new ActionResult(false, "Unknown action: " + actionName);

        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            return new ActionResult(false, "Interrupted: " + e.getMessage());
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            return new ActionResult(false, "Execution error: " + e.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("actionName = %s, args = %s", actionName, arg), e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Handles workflow-related actions (from Interpreter).
     *
     * @param actionName the action name
     * @param arg the argument string
     * @return ActionResult if handled, null if not a workflow action
     */
    private ActionResult handleWorkflowAction(String actionName, String arg)
            throws InterruptedException, ExecutionException {

        switch (actionName) {
            case "execCode":
                return this.ask(n -> n.execCode()).get();

            case "runUntilEnd":
                int maxIterations = parseMaxIterations(arg, 10000);
                return this.ask(n -> n.runUntilEnd(maxIterations)).get();

            case "runWorkflow":
                JSONArray runArgs = new JSONArray(arg);
                String workflowFile = runArgs.getString(0);
                int runMaxIterations = runArgs.length() > 1 ? runArgs.getInt(1) : 10000;
                logger.info(String.format("Running workflow: %s (maxIterations=%d)", workflowFile, runMaxIterations));
                ActionResult result = this.object.runWorkflow(workflowFile, runMaxIterations);
                logger.info(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
                return result;

            case "readYaml":
                return handleReadYaml(arg);

            default:
                return null; // Not a workflow action
        }
    }

    /**
     * Handles NodeGroup-specific actions.
     *
     * @param actionName the action name
     * @param arg the argument string
     * @return ActionResult if handled, null if not a NodeGroup action
     */
    private ActionResult handleNodeGroupAction(String actionName, String arg)
            throws InterruptedException, ExecutionException {

        switch (actionName) {
            case "hasInventory":
                boolean hasInv = this.object.getInventory() != null;
                return new ActionResult(hasInv, hasInv ? "Inventory available" : "No inventory");

            case "createNodeActors":
                String groupName = extractSingleArgument(arg);
                createNodeActors(groupName);
                return new ActionResult(true, String.format("Created node actors for group '%s'", groupName));

            case "apply":
                return apply(arg);

            case "executeCommandOnAllNodes":
                String command = extractSingleArgument(arg);
                List<String> results = executeCommandOnAllNodes(command);
                return new ActionResult(true,
                    String.format("Executed command on %d nodes: %s", results.size(), results));

            case "hasAccumulator":
                // Check if outputMultiplexer exists (loose coupling)
                boolean hasAcc = ((IIActorSystem) this.system()).getIIActor("outputMultiplexer") != null;
                return new ActionResult(hasAcc, hasAcc ? "Accumulator exists" : "No accumulator");

            case "createAccumulator":
                // No-op: MultiplexerAccumulator is now created by RunCLI
                // This case is kept for backward compatibility with existing workflows
                return new ActionResult(true, "Accumulator managed by CLI");

            case "getAccumulatorSummary":
                return getAccumulatorSummary();

            case "printSessionSummary":
                return printSessionSummary();

            case "getSessionId":
                return getSessionIdAction();

            case "doNothing":
                return new ActionResult(true, arg);

            default:
                return null; // Not a NodeGroup action
        }
    }

    // --- Helper methods ---

    private ActionResult handleReadYaml(String arg) throws InterruptedException, ExecutionException {
        String filePath = extractSingleArgument(arg);
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
        }
    }

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
            DistributedLogStore logStore = this.object.getLogStore();
            long sessionId = this.object.getSessionId();

            for (IIActorRef<?> actor : matchedActors) {
                ActionResult result = actor.callByActionName(method, args);
                if (!result.isSuccess()) {
                    failures.add(String.format("%s: %s", actor.getName(), result.getResult()));
                    logger.warning(String.format("Failed on %s: %s", actor.getName(), result.getResult()));
                    // Record node failure in log store
                    if (logStore != null && sessionId >= 0) {
                        logStore.markNodeFailed(sessionId, actor.getName(), result.getResult());
                    }
                } else {
                    successCount++;
                    logger.fine(String.format("Applied to %s: %s", actor.getName(), result.getResult()));
                    // Record node success in log store
                    if (logStore != null && sessionId >= 0) {
                        logStore.markNodeSuccess(sessionId, actor.getName());
                    }
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
     * Gets the current session ID.
     *
     * <p>Returns the session ID for the current workflow execution.
     * This can be used by other actors (like SystemInfoAggregator) to
     * query logs for the current session.</p>
     *
     * @return ActionResult with the session ID as the result string
     */
    private ActionResult getSessionIdAction() {
        long sessionId = this.object.getSessionId();
        if (sessionId < 0) {
            return new ActionResult(false, "No session ID set");
        }
        return new ActionResult(true, String.valueOf(sessionId));
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
        List<String> errorDetails = new ArrayList<>();
        List<String> warnDetails = new ArrayList<>();

        for (String[] mapping : mappings) {
            String label = mapping[0];
            String displayName = mapping[1];
            VerifyResult result = resultsByLabel.get(label);

            if (result == null) {
                sb.append(String.format("| %-35s | %-20s |\n", displayName, "-"));
                continue;
            }

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
        if (totalError == 0 && totalWarn == 0) {
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
