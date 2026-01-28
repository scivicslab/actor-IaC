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
 * Command executor that executes commands on a remote node via SSH.
 *
 * <p>Delegates all operations to the wrapped {@link Node} instance.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class SshCommandExecutor implements CommandExecutor {

    private final Node node;

    /**
     * Constructs an SSH command executor wrapping the given node.
     *
     * @param node the node to execute commands on
     */
    public SshCommandExecutor(Node node) {
        this.node = node;
    }

    @Override
    public Node.CommandResult execute(String command) throws IOException {
        return node.executeCommand(command);
    }

    @Override
    public Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException {
        return node.executeCommand(command, callback);
    }

    @Override
    public Node.CommandResult executeSudo(String command) throws IOException {
        return node.executeSudoCommand(command);
    }

    @Override
    public Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException {
        return node.executeSudoCommand(command, callback);
    }

    @Override
    public String getIdentifier() {
        return node.getHostname();
    }

    /**
     * Gets the wrapped Node instance.
     *
     * @return the node
     */
    public Node getNode() {
        return node;
    }
}
