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
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.lalyos.jfiglet.FigletFont;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for {@link NodeInterpreter} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link NodeInterpreter} objects. It handles action invocations
 * by name, supporting both workflow execution actions (inherited from Interpreter)
 * and infrastructure actions (SSH command execution).</p>
 *
 * <p>Supported actions include:</p>
 * <ul>
 * <li><strong>Workflow actions (from Interpreter):</strong></li>
 *   <ul>
 *   <li>{@code execCode} - Executes the loaded workflow code</li>
 *   <li>{@code readYaml} - Reads a YAML workflow definition from a file path</li>
 *   <li>{@code readJson} - Reads a JSON workflow definition from a file path</li>
 *   <li>{@code readXml} - Reads an XML workflow definition from a file path</li>
 *   <li>{@code reset} - Resets the interpreter state</li>
 *   </ul>
 * <li><strong>Infrastructure actions (Node-specific):</strong></li>
 *   <ul>
 *   <li>{@code executeCommand} - Executes a command and reports to accumulator (default)</li>
 *   <li>{@code executeCommandQuiet} - Executes a command without reporting</li>
 *   <li>{@code executeSudoCommand} - Executes sudo command and reports to accumulator (default)</li>
 *   <li>{@code executeSudoCommandQuiet} - Executes sudo command without reporting</li>
 *   </ul>
 * </ul>
 *
 * <h3>Example YAML Workflow:</h3>
 * <pre>{@code
 * name: deploy-application
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: this
 *         method: executeCommand
 *         arguments:
 *           command: "apt-get update"
 *           type: update
 *   - states: ["1", "end"]
 *     actions:
 *       - actor: this
 *         method: executeCommand
 *         arguments:
 *           command: "ls -la"
 *           type: verify
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeIIAR extends IIActorRef<NodeInterpreter> {

    Logger logger = null;

    /**
     * Constructs a new NodeIIAR with the specified actor name and node interpreter object.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeInterpreter} instance managed by this actor reference
     */
    public NodeIIAR(String actorName, NodeInterpreter object) {
        super(actorName, object);
        logger = Logger.getLogger(actorName);
    }

    /**
     * Constructs a new NodeIIAR with the specified actor name, node interpreter object,
     * and actor system.
     *
     * @param actorName the name of this actor
     * @param object the {@link NodeInterpreter} instance managed by this actor reference
     * @param system the actor system managing this actor
     */
    public NodeIIAR(String actorName, NodeInterpreter object, IIActorSystem system) {
        super(actorName, object, system);
        logger = Logger.getLogger(actorName);

        // Set the selfActorRef in the Interpreter (NodeInterpreter extends Interpreter)
        object.setSelfActorRef(this);
    }

    /**
     * Invokes an action on the node by name with the given arguments.
     *
     * <p>This method handles both Interpreter actions (workflow execution) and
     * Node-specific actions (SSH command execution).</p>
     *
     * <p>For command execution actions ({@code executeCommand}),
     * the {@code arg} parameter should be a JSON array with a single string element
     * containing the command to execute, e.g., {@code ["ls -la"]}.</p>
     *
     * @param actionName the name of the action to execute
     * @param arg the argument string (file path for read operations, JSON array for commands)
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
            else if (actionName.equals("readYaml")) {
                try {
                    String overlayPath = this.object.getOverlayDir();
                    if (overlayPath != null) {
                        // Use overlay: readYaml(Path, Path)
                        java.nio.file.Path yamlPath = java.nio.file.Path.of(arg);
                        java.nio.file.Path overlayDir = java.nio.file.Path.of(overlayPath);
                        this.tell(n -> {
                            try {
                                n.readYaml(yamlPath, overlayDir);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }).get();
                        success = true;
                        message = "YAML loaded with overlay: " + overlayPath;
                    } else {
                        // No overlay: use InputStream
                        try (InputStream input = new FileInputStream(new File(arg))) {
                            this.tell(n -> n.readYaml(input)).get();
                            success = true;
                            message = "YAML loaded successfully";
                        }
                    }
                } catch (FileNotFoundException e) {
                    logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
                    message = "File not found: " + arg;
                } catch (IOException e) {
                    logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
                    message = "IO error: " + arg;
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof IOException) {
                        logger.log(Level.SEVERE, String.format("IOException: %s", arg), e.getCause());
                        message = "IO error: " + arg;
                    } else {
                        throw e;
                    }
                }
            }
            else if (actionName.equals("readJson")) {
                try (InputStream input = new FileInputStream(new File(arg))) {
                    this.tell(n -> {
                        try {
                            n.readJson(input);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).get();
                    success = true;
                    message = "JSON loaded successfully";
                } catch (FileNotFoundException e) {
                    logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
                    message = "File not found: " + arg;
                } catch (IOException e) {
                    logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
                    message = "IO error: " + arg;
                }
            }
            else if (actionName.equals("readXml")) {
                try (InputStream input = new FileInputStream(new File(arg))) {
                    this.tell(n -> {
                        try {
                            n.readXml(input);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }).get();
                    success = true;
                    message = "XML loaded successfully";
                } catch (FileNotFoundException e) {
                    logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
                    message = "File not found: " + arg;
                } catch (Exception e) {
                    logger.log(Level.SEVERE, String.format("Exception: %s", arg), e);
                    message = "Error: " + arg;
                }
            }
            else if (actionName.equals("reset")) {
                this.tell(n -> n.reset()).get();
                success = true;
                message = "Interpreter reset successfully";
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
            else if (actionName.equals("call")) {
                // Subworkflow call (creates child actor) - uses Interpreter.call() method
                JSONArray args = new JSONArray(arg);
                String workflowFile = args.getString(0);
                ActionResult result = this.ask(n -> n.call(workflowFile)).get();
                return result;
            }
            else if (actionName.equals("runWorkflow")) {
                // Load and run workflow directly (no child actor)
                // Note: Call synchronously to avoid deadlock when runWorkflow calls execCode internally
                JSONArray args = new JSONArray(arg);
                String workflowFile = args.getString(0);
                int maxIterations = args.length() > 1 ? args.getInt(1) : 10000;
                logger.fine(String.format("Running workflow: %s (maxIterations=%d)", workflowFile, maxIterations));
                ActionResult result = this.object.runWorkflow(workflowFile, maxIterations);
                logger.fine(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
                return result;
            }
            else if (actionName.equals("apply")) {
                // Apply action to child actors - uses Interpreter.apply() method
                ActionResult result = this.ask(n -> n.apply(arg)).get();
                return result;
            }
            // Node-specific actions (SSH command execution)
            // executeCommandQuiet: Execute without reporting to accumulator
            else if (actionName.equals("executeCommandQuiet")) {
                String command = extractCommandFromArgs(arg);
                Node.CommandResult result = this.ask(n -> {
                    try {
                        return n.executeCommand(command);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();

                success = result.isSuccess();
                message = String.format("exitCode=%d, stdout='%s', stderr='%s'",
                    result.getExitCode(), result.getStdout(), result.getStderr());
            }
            // executeSudoCommandQuiet: Execute sudo without reporting to accumulator
            else if (actionName.equals("executeSudoCommandQuiet")) {
                String command = extractCommandFromArgs(arg);
                Node.CommandResult result = this.ask(n -> {
                    try {
                        return n.executeSudoCommand(command);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();

                success = result.isSuccess();
                message = String.format("exitCode=%d, stdout='%s', stderr='%s'",
                    result.getExitCode(), result.getStdout(), result.getStderr());
            }
            // executeCommand: Execute and report to accumulator (default)
            else if (actionName.equals("executeCommand")) {
                // Object argument: {"command": "...", "type": "cpu"} or {"command": "..."}
                JSONObject json = new JSONObject(arg);
                String command = json.getString("command");

                // Generate label from first 10 characters of command (for accumulator type)
                String bannerText = command.trim();
                if (bannerText.length() > 10) {
                    bannerText = bannerText.substring(0, 10);
                }
                // Remove newlines
                bannerText = bannerText.replace("\n", " ").replace("\r", "");

                // Execute command
                Node.CommandResult result = this.ask(n -> {
                    try {
                        return n.executeCommand(command);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();

                // Report to accumulator (include both stdout and stderr)
                IIActorSystem sys = (IIActorSystem) this.system();
                IIActorRef<?> accumulator = sys.getIIActor("accumulator");
                if (accumulator != null) {
                    JSONObject reportArg = new JSONObject();
                    reportArg.put("source", this.getName());
                    // Use type if provided, otherwise use first 10 chars of command
                    String type = json.optString("type", bannerText);
                    reportArg.put("type", type);
                    // Combine stdout and stderr for complete output
                    String output = result.getStdout().trim();
                    String stderr = result.getStderr().trim();
                    if (!stderr.isEmpty()) {
                        output = output.isEmpty() ? stderr : output + "\n[stderr]\n" + stderr;
                    }
                    reportArg.put("data", output);
                    accumulator.callByActionName("add", reportArg.toString());
                }

                success = result.isSuccess();
                message = result.getStdout();
                String stderr = result.getStderr().trim();
                if (!stderr.isEmpty()) {
                    message = message.isEmpty() ? stderr : message + "\n[stderr]\n" + stderr;
                }
            }
            // executeSudoCommand: Execute sudo and report to accumulator (default)
            else if (actionName.equals("executeSudoCommand")) {
                // Object argument: {"command": "...", "type": "sshd-check"} or {"command": "..."}
                JSONObject json = new JSONObject(arg);
                String command = json.getString("command");

                // Generate label from first 10 characters of command
                String bannerText = command.trim();
                if (bannerText.length() > 10) {
                    bannerText = bannerText.substring(0, 10);
                }
                bannerText = bannerText.replace("\n", " ").replace("\r", "");

                // Execute sudo command
                Node.CommandResult result = this.ask(n -> {
                    try {
                        return n.executeSudoCommand(command);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();

                // Report to accumulator
                IIActorSystem sys = (IIActorSystem) this.system();
                IIActorRef<?> accumulator = sys.getIIActor("accumulator");
                if (accumulator != null) {
                    JSONObject reportArg = new JSONObject();
                    reportArg.put("source", this.getName());
                    String type = json.optString("type", bannerText);
                    reportArg.put("type", type);
                    String output = result.getStdout().trim();
                    String stderr = result.getStderr().trim();
                    if (!stderr.isEmpty()) {
                        output = output.isEmpty() ? stderr : output + "\n[stderr]\n" + stderr;
                    }
                    reportArg.put("data", output);
                    accumulator.callByActionName("add", reportArg.toString());
                }

                success = result.isSuccess();
                message = result.getStdout();
                String stderrSudo = result.getStderr().trim();
                if (!stderrSudo.isEmpty()) {
                    message = message.isEmpty() ? stderrSudo : message + "\n[stderr]\n" + stderrSudo;
                }
            }
            // Utility actions
            else if (actionName.equals("sleep")) {
                try {
                    long millis = Long.parseLong(arg);
                    Thread.sleep(millis);
                    success = true;
                    message = "Slept for " + millis + "ms";
                } catch (NumberFormatException e) {
                    logger.log(Level.SEVERE, String.format("Invalid sleep duration: %s", arg), e);
                    message = "Invalid sleep duration: " + arg;
                }
            }
            else if (actionName.equals("print")) {
                System.out.println(arg);
                success = true;
                message = "Printed: " + arg;
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
            message = "Interrupted: " + e.getMessage();
            logger.warning(String.format("%s: %s", this.getName(), message));
        } catch (ExecutionException e) {
            // Extract root cause message for cleaner output
            Throwable cause = e.getCause();
            while (cause != null && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String rootMsg = cause != null ? cause.getMessage() : e.getMessage();
            message = rootMsg;
            logger.warning(String.format("%s: %s", this.getName(), message));
        }

        return new ActionResult(success, message);
    }

    /**
     * Extracts a command string from JSON array arguments.
     *
     * <p>Expects arguments in the format: {@code ["command string"]}</p>
     *
     * @param arg the JSON array argument string
     * @return the extracted command string
     * @throws IllegalArgumentException if the argument format is invalid
     */
    private String extractCommandFromArgs(String arg) {
        try {
            JSONArray jsonArray = new JSONArray(arg);
            if (jsonArray.length() == 0) {
                throw new IllegalArgumentException("Command arguments cannot be empty");
            }
            return jsonArray.getString(0);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid command argument format. Expected JSON array with command string: " + arg, e);
        }
    }

}
