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

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.ricksbrown.cowsay.Cowsay;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.Vertex;

/**
 * Level 3 wrapper that adds workflow capabilities to a Node POJO.
 *
 * <p>This class extends {@link Interpreter} to provide workflow execution
 * capabilities while delegating SSH operations to a wrapped {@link Node} instance.</p>
 *
 * <p>This demonstrates the three-level architecture of actor-IaC:</p>
 * <ul>
 * <li><strong>Level 1 (POJO):</strong> {@link Node} - pure POJO with SSH functionality</li>
 * <li><strong>Level 2 (Actor):</strong> ActorRef&lt;Node&gt; - actor wrapper for concurrent execution</li>
 * <li><strong>Level 3 (Workflow):</strong> NodeInterpreter - workflow capabilities + IIActorRef wrapper</li>
 * </ul>
 *
 * <p><strong>Design principle:</strong> Node remains a pure POJO, independent of ActorSystem.
 * NodeInterpreter wraps Node to add workflow capabilities without modifying the Node class.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeInterpreter extends Interpreter {

    private static final Logger logger = Logger.getLogger(NodeInterpreter.class.getName());

    /**
     * The wrapped Node POJO that handles actual SSH operations.
     */
    private final Node node;

    /**
     * The overlay directory path for YAML overlay feature.
     */
    private String overlayDir;

    /**
     * The current vertex YAML snippet (first 5 lines) for accumulator reporting.
     */
    private String currentVertexYaml = "";

    /**
     * Constructs a NodeInterpreter that wraps the specified Node.
     *
     * @param node the {@link Node} instance to wrap
     * @param system the actor system for workflow execution
     */
    public NodeInterpreter(Node node, IIActorSystem system) {
        super();
        this.node = node;
        this.system = system;
        // Initialize parent's logger (used by Interpreter.runWorkflow error handling)
        super.logger = logger;
    }

    /**
     * Executes a command on the remote node via SSH.
     *
     * <p>Delegates to the wrapped {@link Node#executeCommand(String)} method.</p>
     *
     * @param command the command to execute
     * @return the result of the command execution
     * @throws IOException if SSH connection fails
     */
    public Node.CommandResult executeCommand(String command) throws IOException {
        return node.executeCommand(command);
    }

    /**
     * Executes a command with sudo privileges on the remote node.
     *
     * <p>Delegates to the wrapped {@link Node#executeSudoCommand(String)} method.
     * Requires SUDO_PASSWORD environment variable to be set.</p>
     *
     * @param command the command to execute with sudo
     * @return the result of the command execution
     * @throws IOException if SSH connection fails or SUDO_PASSWORD is not set
     */
    public Node.CommandResult executeSudoCommand(String command) throws IOException {
        return node.executeSudoCommand(command);
    }

    /**
     * Gets the hostname of the node.
     *
     * @return the hostname
     */
    public String getHostname() {
        return node.getHostname();
    }

    /**
     * Gets the username for SSH connections.
     *
     * @return the username
     */
    public String getUser() {
        return node.getUser();
    }

    /**
     * Gets the SSH port.
     *
     * @return the SSH port number
     */
    public int getPort() {
        return node.getPort();
    }

    /**
     * Gets the wrapped Node instance.
     *
     * <p>This allows direct access to the underlying POJO when needed.</p>
     *
     * @return the wrapped Node
     */
    public Node getNode() {
        return node;
    }

    /**
     * Sets the overlay directory for YAML overlay feature.
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
     * Hook called when entering a vertex during workflow execution.
     *
     * <p>Displays the first 5 lines of the vertex definition in YAML format
     * using cowsay to provide visual separation between workflow steps.</p>
     *
     * @param vertex the vertex being entered
     */
    @Override
    protected void onEnterVertex(Vertex vertex) {
        // Get YAML-formatted output (first 5 lines)
        String yamlText = vertex.toYamlString(5).trim();
        this.currentVertexYaml = yamlText;
        String[] cowsayArgs = { yamlText };
        System.out.println(Cowsay.say(cowsayArgs));
    }

    /**
     * Returns the current vertex YAML snippet for accumulator reporting.
     *
     * @return the first 5 lines of the current vertex in YAML format
     */
    public String getCurrentVertexYaml() {
        return currentVertexYaml;
    }

    /**
     * Loads and runs a workflow file to completion with overlay support.
     *
     * <p>If overlayDir is set, the workflow is loaded with overlay applied.
     * Variables defined in overlay-conf.yaml are substituted before execution.</p>
     *
     * @param workflowFile the workflow file path (YAML or JSON)
     * @param maxIterations maximum number of state transitions allowed
     * @return ActionResult with success=true if completed, false otherwise
     */
    @Override
    public ActionResult runWorkflow(String workflowFile, int maxIterations) {
        // If no overlay is set, use parent implementation
        if (overlayDir == null) {
            return super.runWorkflow(workflowFile, maxIterations);
        }

        try {
            // Reset state for fresh execution
            reset();

            // Resolve workflow file path
            Path workflowPath;
            if (workflowBaseDir != null) {
                workflowPath = Path.of(workflowBaseDir, workflowFile);
            } else {
                workflowPath = Path.of(workflowFile);
            }

            // Load workflow with overlay applied
            Path overlayPath = Path.of(overlayDir);
            readYaml(workflowPath, overlayPath);

            // Run until end
            return runUntilEnd(maxIterations);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error running workflow with overlay: " + workflowFile, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}
