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

import com.scivicslab.actoriac.Node;

/**
 * Abstraction for command execution that can be implemented for different contexts.
 *
 * <p>This interface allows the same action implementation to work in different
 * execution environments:</p>
 * <ul>
 *   <li>{@link SshCommandExecutor} - Executes commands on remote nodes via SSH</li>
 *   <li>{@link LocalCommandExecutor} - Executes commands on the local machine</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public interface CommandExecutor {

    /**
     * Executes a command and returns the result.
     *
     * @param command the command to execute
     * @return the result of command execution
     * @throws IOException if command execution fails
     */
    Node.CommandResult execute(String command) throws IOException;

    /**
     * Executes a command and returns the result with output callback.
     *
     * @param command the command to execute
     * @param callback the callback for real-time output (may be null)
     * @return the result of command execution
     * @throws IOException if command execution fails
     */
    Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException;

    /**
     * Executes a command with sudo privileges.
     *
     * @param command the command to execute with sudo
     * @return the result of command execution
     * @throws IOException if command execution fails or SUDO_PASSWORD is not set
     */
    Node.CommandResult executeSudo(String command) throws IOException;

    /**
     * Executes a command with sudo privileges and output callback.
     *
     * @param command the command to execute with sudo
     * @param callback the callback for real-time output (may be null)
     * @return the result of command execution
     * @throws IOException if command execution fails or SUDO_PASSWORD is not set
     */
    Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException;

    /**
     * Gets a short identifier for this executor (e.g., hostname).
     *
     * @return the identifier string
     */
    String getIdentifier();
}
