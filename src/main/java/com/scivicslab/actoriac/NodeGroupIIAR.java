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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;

import com.scivicslab.pojoactor.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for {@link NodeGroup} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link NodeGroup} objects. It manages groups of infrastructure
 * nodes and can apply workflows to all nodes in a group simultaneously.</p>
 *
 * <p>Supported actions include:</p>
 * <ul>
 * <li>{@code createNodeActors} - Creates child actors for all nodes in a specified group</li>
 * <li>{@code applyWorkflowToAllNodes} - Loads and executes a workflow on all node actors</li>
 * <li>{@code executeCommandOnAllNodes} - Executes a single command on all nodes</li>
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
 *   - states: [0, 1]
 *     actions:
 *       - actor: nodeGroup
 *         method: createNodeActors
 *         arguments: ["web-servers"]
 *   - states: [1, 2]
 *     actions:
 *       - actor: nodeGroup
 *         method: applyWorkflowToAllNodes
 *         arguments: ["deploy-workflow.yaml"]
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeGroupIIAR extends IIActorRef<NodeGroup> {

    Logger logger = null;

    /**
     * Constructs a new NodeGroupIIAR with the specified actor name and nodeGroup object.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeGroup} instance managed by this actor reference
     */
    public NodeGroupIIAR(String actorName, NodeGroup object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Constructs a new NodeGroupIIAR with the specified actor name, nodeGroup object,
     * and actor system.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeGroup} instance managed by this actor reference
     * @param system the actor system managing this actor
     */
    public NodeGroupIIAR(String actorName, NodeGroup object, IIActorSystem system) {
        super(actorName, object, system);
        logger = Logger.getLogger(actorName);
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
            if (actionName.equals("createNodeActors")) {
                String groupName = extractSingleArgument(arg);
                createNodeActors(groupName);
                success = true;
                message = String.format("Created node actors for group '%s'", groupName);
            }
            else if (actionName.equals("applyWorkflowToAllNodes")) {
                String workflowPath = extractSingleArgument(arg);
                applyWorkflowToAllNodes(workflowPath);
                success = true;
                message = String.format("Applied workflow '%s' to all nodes", workflowPath);
            }
            else if (actionName.equals("executeCommandOnAllNodes")) {
                String command = extractSingleArgument(arg);
                List<String> results = executeCommandOnAllNodes(command);
                success = true;
                message = String.format("Executed command on %d nodes: %s", results.size(), results);
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
     * @param groupName the name of the group from the inventory file
     * @throws ExecutionException if actor creation fails
     * @throws InterruptedException if the operation is interrupted
     */
    private void createNodeActors(String groupName) throws ExecutionException, InterruptedException {
        this.tell(nodeGroup -> {
            IIActorSystem system = (IIActorSystem) this.system();

            // Create Node POJOs for the group
            List<Node> nodes = nodeGroup.createNodesForGroup(groupName);

            // Create child actors for each node
            for (Node node : nodes) {
                String nodeName = "node-" + node.getHostname();

                // Wrap Node in NodeInterpreter to add workflow capabilities
                NodeInterpreter nodeInterpreter = new NodeInterpreter(node, system);

                // Create child actor using ActorRef.createChild()
                // This establishes parent-child relationship
                this.createChild(nodeName, nodeInterpreter);

                // Also wrap in NodeIIAR and add to system for workflow execution
                NodeIIAR nodeActor = new NodeIIAR(nodeName, nodeInterpreter, system);
                system.addIIActor(nodeActor);

                logger.info(String.format("Created child node actor: %s", nodeName));
            }
        }).get();
    }

    /**
     * Applies a workflow to all child node actors.
     *
     * <p>This method loads the specified workflow file and executes it on each
     * node actor that is a child of this NodeGroup actor. The workflow is
     * executed using the Node's Interpreter capabilities.</p>
     *
     * <p>Workflow execution steps:</p>
     * <ol>
     * <li>Get all child node actors using {@code getNamesOfChildren()}</li>
     * <li>For each child node:</li>
     *   <ul>
     *   <li>Load the workflow YAML/JSON/XML file via {@code readYaml/readJson/readXml}</li>
     *   <li>Execute the workflow repeatedly using {@code execCode} until completion</li>
     *   </ul>
     * </ol>
     *
     * @param workflowPath path to the workflow file (YAML, JSON, or XML)
     * @throws IOException if the workflow file cannot be read
     * @throws ExecutionException if workflow execution fails
     * @throws InterruptedException if the operation is interrupted
     */
    private void applyWorkflowToAllNodes(String workflowPath)
            throws IOException, ExecutionException, InterruptedException {

        IIActorSystem system = (IIActorSystem) this.system();

        // Determine file type from extension
        String fileType = getFileExtension(workflowPath);
        String loadMethod;
        if (fileType.equals("yaml") || fileType.equals("yml")) {
            loadMethod = "readYaml";
        } else if (fileType.equals("json")) {
            loadMethod = "readJson";
        } else if (fileType.equals("xml")) {
            loadMethod = "readXml";
        } else {
            throw new IllegalArgumentException("Unsupported workflow file type: " + fileType);
        }

        // Get all child node names
        List<String> childNames = new ArrayList<>(this.getNamesOfChildren());

        logger.info(String.format("Applying workflow '%s' to %d nodes", workflowPath, childNames.size()));

        // Apply workflow to each child node
        for (String childName : childNames) {
            IIActorRef<?> actorRef = system.getIIActor(childName);
            if (actorRef == null || !(actorRef instanceof NodeIIAR)) {
                logger.warning(String.format("Child node actor not found or wrong type: %s", childName));
                continue;
            }
            NodeIIAR nodeActor = (NodeIIAR) actorRef;

            logger.info(String.format("Loading workflow on node: %s", childName));

            // Load the workflow on the node
            ActionResult loadResult = nodeActor.callByActionName(loadMethod, workflowPath);
            if (!loadResult.isSuccess()) {
                logger.warning(String.format("Failed to load workflow on %s: %s",
                    childName, loadResult.getResult()));
                continue;
            }

            // Execute the workflow until completion
            logger.info(String.format("Executing workflow on node: %s", childName));
            int maxIterations = 100; // Prevent infinite loops
            for (int i = 0; i < maxIterations; i++) {
                ActionResult execResult = nodeActor.callByActionName("execCode", "");

                if (!execResult.isSuccess()) {
                    logger.warning(String.format("Workflow execution stopped on %s: %s",
                        childName, execResult.getResult()));
                    break;
                }

                // Check if workflow reached end state
                if (execResult.getResult().contains("end") ||
                    execResult.getResult().contains("complete")) {
                    logger.info(String.format("Workflow completed on %s", childName));
                    break;
                }
            }

            // Reset the interpreter for reuse
            nodeActor.callByActionName("reset", "");
        }
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
     * Gets the file extension from a file path.
     *
     * @param path the file path
     * @return the file extension (lowercase)
     */
    private String getFileExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0 && lastDot < path.length() - 1) {
            return path.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

}
