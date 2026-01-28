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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import static com.scivicslab.pojoactor.core.ActionArgs.getFirst;

/**
 * Interpreter-interfaced actor reference for {@link NodeInterpreter} instances.
 *
 * <p>This class provides a concrete implementation of {@link IIActorRef}
 * specifically for {@link NodeInterpreter} objects. It handles action invocations
 * by name, supporting both workflow execution actions (inherited from Interpreter)
 * and infrastructure actions (SSH command execution).</p>
 *
 * <p><strong>Supported actions:</strong></p>
 * <p><em>Workflow actions (from Interpreter):</em></p>
 * <ul>
 *   <li>{@code execCode} - Executes the loaded workflow code</li>
 *   <li>{@code readYaml} - Reads a YAML workflow definition from a file path</li>
 *   <li>{@code readJson} - Reads a JSON workflow definition from a file path</li>
 *   <li>{@code readXml} - Reads an XML workflow definition from a file path</li>
 *   <li>{@code reset} - Resets the interpreter state</li>
 * </ul>
 * <p><em>Infrastructure actions (Node-specific):</em></p>
 * <ul>
 *   <li>{@code executeCommand} - Executes a command and reports to accumulator (default)</li>
 *   <li>{@code executeCommandQuiet} - Executes a command without reporting</li>
 *   <li>{@code executeSudoCommand} - Executes sudo command and reports to accumulator (default)</li>
 *   <li>{@code executeSudoCommandQuiet} - Executes sudo command without reporting</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
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

    // ========================================================================
    // Workflow Actions
    // ========================================================================

    @Action("execCode")
    public ActionResult execCode(String args) {
        try {
            return this.ask(n -> n.execCode()).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("readYaml")
    public ActionResult readYaml(String args) {
        String arg = getFirst(args);
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
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                logger.log(Level.SEVERE, String.format("IOException: %s", arg), e.getCause());
                return new ActionResult(false, "IO error: " + arg);
            }
            throw e;
        }
    }

    @Action("readJson")
    public ActionResult readJson(String args) {
        String arg = getFirst(args);
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
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("readXml")
    public ActionResult readXml(String args) {
        String arg = getFirst(args);
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

    @Action("reset")
    public ActionResult reset(String args) {
        try {
            this.tell(n -> n.reset()).get();
            return new ActionResult(true, "Interpreter reset successfully");
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("runUntilEnd")
    public ActionResult runUntilEnd(String args) {
        try {
            int maxIterations = parseMaxIterations(args, 10000);
            return this.ask(n -> n.runUntilEnd(maxIterations)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("call")
    public ActionResult call(String args) {
        try {
            JSONArray callArgs = new JSONArray(args);
            String callWorkflowFile = callArgs.getString(0);
            return this.ask(n -> n.call(callWorkflowFile)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("runWorkflow")
    public ActionResult runWorkflow(String args) {
        try {
            JSONArray runArgs = new JSONArray(args);
            String runWorkflowFile = runArgs.getString(0);
            int runMaxIterations = runArgs.length() > 1 ? runArgs.getInt(1) : 10000;
            logger.fine(String.format("Running workflow: %s (maxIterations=%d)", runWorkflowFile, runMaxIterations));
            ActionResult result = this.object.runWorkflow(runWorkflowFile, runMaxIterations);
            logger.fine(String.format("Workflow completed: success=%s, result=%s", result.isSuccess(), result.getResult()));
            return result;
        } catch (Exception e) {
            return handleException(e);
        }
    }

    @Action("apply")
    public ActionResult apply(String args) {
        try {
            return this.ask(n -> n.apply(args)).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    // ========================================================================
    // Command Execution Actions
    // ========================================================================

    @Action("executeCommand")
    public ActionResult executeCommand(String args) {
        try {
            String command = extractCommandFromArgs(args);
            String nodeName = this.getName();

            Node.OutputCallback callback = createOutputCallback(nodeName);

            Node.CommandResult result = this.ask(n -> {
                try {
                    return n.executeCommand(command, callback);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();

            reportToAccumulator(result);
            return new ActionResult(result.isSuccess(), combineOutput(result));
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("executeCommandQuiet")
    public ActionResult executeCommandQuiet(String args) {
        try {
            String command = extractCommandFromArgs(args);
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
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("executeSudoCommand")
    public ActionResult executeSudoCommand(String args) {
        try {
            String command = extractCommandFromArgs(args);
            String nodeName = this.getName();

            Node.OutputCallback callback = createOutputCallback(nodeName);

            Node.CommandResult result = this.ask(n -> {
                try {
                    return n.executeSudoCommand(command, callback);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get();

            reportToAccumulator(result);
            return new ActionResult(result.isSuccess(), combineOutput(result));
        } catch (ExecutionException e) {
            // Check if this is a SUDO_PASSWORD error
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null &&
                cause.getMessage().contains("SUDO_PASSWORD environment variable is not set")) {
                String hostname = this.object.getHostname();
                String errorMessage = "%" + hostname + ": [FAIL] SUDO_PASSWORD not set";
                reportOutputToMultiplexer(this.getName(), errorMessage);
                return new ActionResult(false, errorMessage);
            }
            return handleException(e);
        } catch (InterruptedException e) {
            return handleException(e);
        }
    }

    @Action("executeSudoCommandQuiet")
    public ActionResult executeSudoCommandQuiet(String args) {
        try {
            String command = extractCommandFromArgs(args);
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
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    // ========================================================================
    // Utility Actions
    // ========================================================================

    @Action("sleep")
    public ActionResult sleep(String args) {
        try {
            long millis = Long.parseLong(getFirst(args));
            Thread.sleep(millis);
            return new ActionResult(true, "Slept for " + millis + "ms");
        } catch (NumberFormatException e) {
            logger.log(Level.SEVERE, String.format("Invalid sleep duration: %s", args), e);
            return new ActionResult(false, "Invalid sleep duration: " + args);
        } catch (InterruptedException e) {
            return new ActionResult(false, "Sleep interrupted: " + e.getMessage());
        }
    }

    @Action("print")
    public ActionResult print(String args) {
        String text = getFirst(args);
        System.out.println(text);
        return new ActionResult(true, "Printed: " + text);
    }

    @Action("doNothing")
    public ActionResult doNothing(String args) {
        return new ActionResult(true, getFirst(args));
    }

    // ========================================================================
    // Document Workflow Actions
    // ========================================================================

    @Action("detectDocumentChanges")
    public ActionResult detectDocumentChanges(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.detectDocumentChanges(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error detecting changes: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("cloneChangedDocuments")
    public ActionResult cloneChangedDocuments(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.cloneChangedDocuments(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error cloning documents: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("buildChangedDocuments")
    public ActionResult buildChangedDocuments(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.buildChangedDocuments(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error building documents: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
    }

    @Action("deployChangedDocuments")
    public ActionResult deployChangedDocuments(String args) {
        try {
            return this.ask(n -> {
                try {
                    String docListPath = extractCommandFromArgs(args);
                    return n.deployChangedDocuments(docListPath);
                } catch (IOException e) {
                    return new ActionResult(false, "Error deploying documents: " + e.getMessage());
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            return handleException(e);
        }
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
        logger.info(String.format("printYaml called: path='%s'", path));
        String formatted = toStringOfYaml(path);
        logger.info(String.format("printYaml output length: %d", formatted.length()));
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

    /**
     * Handles exceptions and returns an appropriate ActionResult.
     */
    private ActionResult handleException(Exception e) {
        String message;
        if (e instanceof ExecutionException) {
            message = extractRootCauseMessage((ExecutionException) e);
        } else {
            message = e.getMessage();
        }
        logger.warning(String.format("%s: %s", this.getName(), message));
        return new ActionResult(false, message);
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
     * Creates an OutputCallback that forwards output to the multiplexer accumulator.
     */
    private Node.OutputCallback createOutputCallback(String nodeName) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");

        if (multiplexer == null) {
            return null;
        }

        return new Node.OutputCallback() {
            @Override
            public void onStdout(String line) {
                JSONObject arg = new JSONObject();
                arg.put("source", nodeName);
                arg.put("type", "stdout");
                arg.put("data", line);
                multiplexer.callByActionName("add", arg.toString());
            }

            @Override
            public void onStderr(String line) {
                JSONObject arg = new JSONObject();
                arg.put("source", nodeName);
                arg.put("type", "stderr");
                arg.put("data", line);
                multiplexer.callByActionName("add", arg.toString());
            }
        };
    }

    /**
     * Reports command result to the multiplexer accumulator actor if available.
     */
    private void reportToAccumulator(Node.CommandResult result) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");
        if (multiplexer != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", this.getName());
            reportArg.put("type", this.object.getCurrentTransitionYaml());
            reportArg.put("data", combineOutput(result));
            multiplexer.callByActionName("add", reportArg.toString());
        }
    }

    /**
     * Reports a message to the multiplexer accumulator.
     */
    private void reportOutputToMultiplexer(String nodeName, String message) {
        IIActorSystem sys = (IIActorSystem) this.system();
        IIActorRef<?> multiplexer = sys.getIIActor("outputMultiplexer");
        if (multiplexer != null) {
            JSONObject reportArg = new JSONObject();
            reportArg.put("source", nodeName);
            reportArg.put("type", "error");
            reportArg.put("data", message);
            multiplexer.callByActionName("add", reportArg.toString());
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
     * Extracts a meaningful error message from an ExecutionException.
     */
    private String extractRootCauseMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        Throwable current = cause;
        while (current != null) {
            if (current instanceof java.io.IOException) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return cause != null ? cause.getMessage() : e.getMessage();
    }

    /**
     * Extracts a command string from JSON array arguments.
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
