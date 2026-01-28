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

package com.scivicslab.actoriac.mixin;

import java.io.IOException;

import org.json.JSONArray;

import com.scivicslab.actoriac.Node;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Mixin interface providing command execution actions via @Action annotations.
 *
 * <p>This interface demonstrates the mixin pattern in Java using interface default methods
 * with {@link Action} annotations. Classes implementing this interface automatically gain
 * the ability to execute commands from workflow YAML without duplicating code.</p>
 *
 * <h2>Usage</h2>
 *
 * <p>Implement this interface and provide a {@link CommandExecutor}:</p>
 * <pre>{@code
 * public class NodeInterpreter extends Interpreter implements CommandExecutable {
 *     private final CommandExecutor executor;
 *
 *     public NodeInterpreter(Node node, IIActorSystem system) {
 *         this.executor = new SshCommandExecutor(node);
 *     }
 *
 *     @Override
 *     public CommandExecutor getCommandExecutor() {
 *         return executor;
 *     }
 * }
 * }</pre>
 *
 * <h2>Workflow YAML</h2>
 *
 * <p>Once implemented, the following actions become available in workflow YAML:</p>
 * <pre>{@code
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: this
 *         method: executeCommand
 *         arguments: ["ls -la"]
 *
 *   - states: ["1", "2"]
 *     actions:
 *       - actor: this
 *         method: executeSudoCommand
 *         arguments: ["apt-get update"]
 * }</pre>
 *
 * <h2>Design Rationale</h2>
 *
 * <p>This interface solves the problem of code duplication between NodeInterpreter
 * and NodeGroupInterpreter. Previously, each class needed its own implementation of
 * command execution actions. With this mixin approach:</p>
 * <ul>
 *   <li>Both classes implement the same interface</li>
 *   <li>The @Action methods are defined once in the interface</li>
 *   <li>Each class provides its own CommandExecutor (SSH or local)</li>
 *   <li>IIActorRef discovers the @Action methods via reflection</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 * @see CommandExecutor
 * @see SshCommandExecutor
 * @see LocalCommandExecutor
 */
public interface CommandExecutable {

    /**
     * Returns the command executor for this instance.
     *
     * <p>Implementing classes must provide an appropriate executor:</p>
     * <ul>
     *   <li>{@link SshCommandExecutor} for remote node execution</li>
     *   <li>{@link LocalCommandExecutor} for local execution</li>
     * </ul>
     *
     * @return the command executor
     */
    CommandExecutor getCommandExecutor();

    /**
     * Returns an optional output callback for command execution.
     *
     * <p>When not null, command output is streamed to this callback in real-time.
     * Default implementation returns null (no streaming).</p>
     *
     * @return the output callback, or null
     */
    default Node.OutputCallback getOutputCallback() {
        return null;
    }

    /**
     * Executes a command and returns the result (action handler).
     *
     * <p>This action is callable from workflow YAML as:</p>
     * <pre>{@code
     * - actor: this
     *   method: executeCommand
     *   arguments: ["your-command-here"]
     * }</pre>
     *
     * <p>Note: The Java method name is different from the action name to avoid
     * conflicts with existing methods on implementing classes that have different
     * return types.</p>
     *
     * @param args JSON array containing the command as the first element
     * @return ActionResult with success status and command output
     */
    @Action("executeCommand")
    default ActionResult doExecuteCommand(String args) {
        try {
            String command = extractCommand(args);
            Node.OutputCallback callback = getOutputCallback();
            Node.CommandResult result = getCommandExecutor().execute(command, callback);
            return toActionResult(result);
        } catch (IOException e) {
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Executes a command with sudo privileges (action handler).
     *
     * <p>This action is callable from workflow YAML as:</p>
     * <pre>{@code
     * - actor: this
     *   method: executeSudoCommand
     *   arguments: ["your-command-here"]
     * }</pre>
     *
     * <p>Requires SUDO_PASSWORD environment variable to be set.</p>
     *
     * <p>Note: The Java method name is different from the action name to avoid
     * conflicts with existing methods on implementing classes that have different
     * return types.</p>
     *
     * @param args JSON array containing the command as the first element
     * @return ActionResult with success status and command output
     */
    @Action("executeSudoCommand")
    default ActionResult doExecuteSudoCommand(String args) {
        try {
            String command = extractCommand(args);
            Node.OutputCallback callback = getOutputCallback();
            Node.CommandResult result = getCommandExecutor().executeSudo(command, callback);
            return toActionResult(result);
        } catch (IOException e) {
            String hostname = getCommandExecutor().getIdentifier();
            if (e.getMessage() != null && e.getMessage().contains("SUDO_PASSWORD")) {
                return new ActionResult(false, "%" + hostname + ": [FAIL] SUDO_PASSWORD not set");
            }
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Extracts the command string from JSON array arguments.
     *
     * @param args JSON array string (e.g., '["ls -la"]')
     * @return the extracted command string
     */
    private static String extractCommand(String args) {
        try {
            JSONArray jsonArray = new JSONArray(args);
            if (jsonArray.length() == 0) {
                throw new IllegalArgumentException("Command arguments cannot be empty");
            }
            return jsonArray.getString(0);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid command argument format. Expected JSON array with command string: " + args, e);
        }
    }

    /**
     * Converts a Node.CommandResult to ActionResult.
     *
     * @param result the command result
     * @return the action result
     */
    private static ActionResult toActionResult(Node.CommandResult result) {
        String output = result.getStdout().trim();
        String stderr = result.getStderr().trim();
        if (!stderr.isEmpty()) {
            output = output.isEmpty() ? stderr : output + "\n[stderr]\n" + stderr;
        }
        return new ActionResult(result.isSuccess(), output);
    }
}
