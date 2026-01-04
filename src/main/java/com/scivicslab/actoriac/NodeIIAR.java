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
 *           - "apt-get update"
 *   - states: ["1", "end"]
 *     actions:
 *       - actor: this
 *         method: executeCommand
 *         arguments:
 *           - "ls -la"
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
     * <p>This method dispatches to specialized handler methods based on the action type:</p>
     * <ul>
     *   <li>Workflow actions: {@link #handleWorkflowAction}</li>
     *   <li>Command execution actions: {@link #handleCommandAction}</li>
     *   <li>Utility actions: {@link #handleUtilityAction}</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param arg the argument string (file path for read operations, JSON array for commands)
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

            // Node-specific actions (SSH command execution)
            ActionResult commandResult = handleCommandAction(actionName, arg);
            if (commandResult != null) {
                return commandResult;
            }

            // Utility actions
            ActionResult utilityResult = handleUtilityAction(actionName, arg);
            if (utilityResult != null) {
                return utilityResult;
            }

            // Unknown action
            logger.log(Level.SEVERE, String.format("Unknown action: actorName = %s, action = %s, arg = %s",
                    this.getName(), actionName, arg));
            return new ActionResult(false, "Unknown action: " + actionName);

        } catch (InterruptedException e) {
            String message = "Interrupted: " + e.getMessage();
            logger.warning(String.format("%s: %s", this.getName(), message));
            return new ActionResult(false, message);
        } catch (ExecutionException e) {
            String message = extractRootCauseMessage(e);
            logger.warning(String.format("%s: %s", this.getName(), message));
            return new ActionResult(false, message);
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

            case "readYaml":
                return handleReadYaml(arg);

            case "readJson":
                return handleReadJson(arg);

            case "readXml":
                return handleReadXml(arg);

            case "reset":
                this.tell(n -> n.reset()).get();
                return new ActionResult(true, "Interpreter reset successfully");

            case "runUntilEnd":
                int maxIterations = parseMaxIterations(arg, 10000);
                return this.ask(n -> n.runUntilEnd(maxIterations)).get();

            case "call":
                JSONArray callArgs = new JSONArray(arg);
                String callWorkflowFile = callArgs.getString(0);
                return this.ask(n -> n.call(callWorkflowFile)).get();

            case "runWorkflow":
                JSONArray runArgs = new JSONArray(arg);
                String runWorkflowFile = runArgs.getString(0);
                int runMaxIterations = runArgs.length() > 1 ? runArgs.getInt(1) : 10000;
                logger.fine(String.format("Running workflow: %s (maxIterations=%d)", runWorkflowFile, runMaxIterations));
                ActionResult result = this.object.runWorkflow(runWorkflowFile, runMaxIterations);
                logger.fine(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
                return result;

            case "apply":
                return this.ask(n -> n.apply(arg)).get();

            default:
                return null; // Not a workflow action
        }
    }

    /**
     * Handles SSH command execution actions.
     *
     * @param actionName the action name
     * @param arg the argument string
     * @return ActionResult if handled, null if not a command action
     */
    private ActionResult handleCommandAction(String actionName, String arg)
            throws InterruptedException, ExecutionException {

        switch (actionName) {
            case "executeCommandQuiet":
                return executeCommandQuiet(arg);

            case "executeSudoCommandQuiet":
                return executeSudoCommandQuiet(arg);

            case "executeCommand":
                return executeCommandWithReport(arg);

            case "executeSudoCommand":
                return executeSudoCommandWithReport(arg);

            default:
                return null; // Not a command action
        }
    }

    /**
     * Handles utility actions (sleep, print, doNothing).
     *
     * @param actionName the action name
     * @param arg the argument string
     * @return ActionResult if handled, null if not a utility action
     */
    private ActionResult handleUtilityAction(String actionName, String arg) {
        switch (actionName) {
            case "sleep":
                try {
                    long millis = Long.parseLong(arg);
                    Thread.sleep(millis);
                    return new ActionResult(true, "Slept for " + millis + "ms");
                } catch (NumberFormatException e) {
                    logger.log(Level.SEVERE, String.format("Invalid sleep duration: %s", arg), e);
                    return new ActionResult(false, "Invalid sleep duration: " + arg);
                } catch (InterruptedException e) {
                    return new ActionResult(false, "Sleep interrupted: " + e.getMessage());
                }

            case "print":
                System.out.println(arg);
                return new ActionResult(true, "Printed: " + arg);

            case "doNothing":
                return new ActionResult(true, arg);

            default:
                return null; // Not a utility action
        }
    }

    // --- Helper methods for workflow actions ---

    private ActionResult handleReadYaml(String arg) throws InterruptedException, ExecutionException {
        try {
            String overlayPath = this.object.getOverlayDir();
            if (overlayPath != null) {
                java.nio.file.Path yamlPath = java.nio.file.Path.of(arg);
                java.nio.file.Path overlayDir = java.nio.file.Path.of(overlayPath);
                this.tell(n -> {
                    try {
                        n.readYaml(yamlPath, overlayDir);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).get();
                return new ActionResult(true, "YAML loaded with overlay: " + overlayPath);
            } else {
                try (InputStream input = new FileInputStream(new File(arg))) {
                    this.tell(n -> n.readYaml(input)).get();
                    return new ActionResult(true, "YAML loaded successfully");
                }
            }
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
            return new ActionResult(false, "File not found: " + arg);
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
            return new ActionResult(false, "IO error: " + arg);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                logger.log(Level.SEVERE, String.format("IOException: %s", arg), e.getCause());
                return new ActionResult(false, "IO error: " + arg);
            }
            throw e;
        }
    }

    private ActionResult handleReadJson(String arg) throws InterruptedException, ExecutionException {
        try (InputStream input = new FileInputStream(new File(arg))) {
            this.tell(n -> {
                try {
                    n.readJson(input);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();
            return new ActionResult(true, "JSON loaded successfully");
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
            return new ActionResult(false, "File not found: " + arg);
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("IOException: %s", arg), e);
            return new ActionResult(false, "IO error: " + arg);
        }
    }

    private ActionResult handleReadXml(String arg) throws InterruptedException, ExecutionException {
        try (InputStream input = new FileInputStream(new File(arg))) {
            this.tell(n -> {
                try {
                    n.readXml(input);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get();
            return new ActionResult(true, "XML loaded successfully");
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, String.format("file not found: %s", arg), e);
            return new ActionResult(false, "File not found: " + arg);
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("Exception: %s", arg), e);
            return new ActionResult(false, "Error: " + arg);
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

    // --- Helper methods for command actions ---

    private ActionResult executeCommandQuiet(String arg) throws InterruptedException, ExecutionException {
        String command = extractCommandFromArgs(arg);
        Node.CommandResult result = this.ask(n -> {
            try {
                return n.executeCommand(command);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get();

        return new ActionResult(result.isSuccess(),
            String.format("exitCode=%d, stdout='%s', stderr='%s'",
                result.getExitCode(), result.getStdout(), result.getStderr()));
    }

    private ActionResult executeSudoCommandQuiet(String arg) throws InterruptedException, ExecutionException {
        String command = extractCommandFromArgs(arg);
        Node.CommandResult result = this.ask(n -> {
            try {
                return n.executeSudoCommand(command);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get();

        return new ActionResult(result.isSuccess(),
            String.format("exitCode=%d, stdout='%s', stderr='%s'",
                result.getExitCode(), result.getStdout(), result.getStderr()));
    }

    private ActionResult executeCommandWithReport(String arg) throws InterruptedException, ExecutionException {
        String command = extractCommandFromArgs(arg);
        Node.CommandResult result = this.ask(n -> {
            try {
                return n.executeCommand(command);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get();

        reportToAccumulator(result);
        return new ActionResult(result.isSuccess(), combineOutput(result));
    }

    private ActionResult executeSudoCommandWithReport(String arg) throws InterruptedException, ExecutionException {
        String command = extractCommandFromArgs(arg);
        Node.CommandResult result = this.ask(n -> {
            try {
                return n.executeSudoCommand(command);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get();

        reportToAccumulator(result);
        return new ActionResult(result.isSuccess(), combineOutput(result));
    }

    /**
     * Reports command result to the accumulator actor if available.
     */
    private void reportToAccumulator(Node.CommandResult result) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> accumulator = sys.getIIActor("accumulator");
        if (accumulator != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", this.getName());
            reportArg.put("type", this.object.getCurrentVertexYaml());
            reportArg.put("data", combineOutput(result));
            accumulator.callByActionName("add", reportArg.toString());
        }
    }

    /**
     * Combines stdout and stderr into a single output string.
     */
    private String combineOutput(Node.CommandResult result) {
        String output = result.getStdout().trim();
        String stderr = result.getStderr().trim();
        if (!stderr.isEmpty()) {
            output = output.isEmpty() ? stderr : output + "\n[stderr]\n" + stderr;
        }
        return output;
    }

    /**
     * Extracts the root cause message from an ExecutionException.
     */
    private String extractRootCauseMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause != null ? cause.getMessage() : e.getMessage();
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
