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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single node in the infrastructure.
 *
 * <p>This class provides SSH-based command execution on remote nodes.
 * Each command execution creates a new SSH connection, executes the command,
 * and returns the result with stdout and stderr.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Node {

    private final String hostname;
    private final String user;
    private final int port;
    private final String identityFile;

    /**
     * Constructs a Node with the specified connection parameters.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     * @param identityFile the path to SSH private key file (can be null)
     */
    public Node(String hostname, String user, int port, String identityFile) {
        this.hostname = hostname;
        this.user = user;
        this.port = port;
        this.identityFile = identityFile;
    }

    /**
     * Constructs a Node with default port 22 and no identity file.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     */
    public Node(String hostname, String user) {
        this(hostname, user, 22, null);
    }

    /**
     * Executes a command on the remote node via SSH.
     *
     * @param command the command to execute
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if SSH connection or command execution fails
     */
    public CommandResult executeCommand(String command) throws IOException {
        List<String> sshCommand = buildSshCommand(command);

        ProcessBuilder pb = new ProcessBuilder(sshCommand);
        Process process = pb.start();

        // Read stdout
        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
        }

        // Read stderr
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        // Wait for process to complete
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        }

        return new CommandResult(
            stdout.toString().trim(),
            stderr.toString().trim(),
            exitCode
        );
    }

    /**
     * Builds the SSH command with appropriate options.
     *
     * @param command the command to execute on the remote host
     * @return the complete SSH command as a list of strings
     */
    private List<String> buildSshCommand(String command) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-p");
        cmd.add(String.valueOf(port));

        if (identityFile != null) {
            cmd.add("-i");
            cmd.add(identityFile);
        }

        // Disable strict host key checking for simplicity
        // In production, you might want to make this configurable
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=/dev/null");

        cmd.add(user + "@" + hostname);
        cmd.add(command);

        return cmd;
    }

    /**
     * Gets the hostname of this node.
     *
     * @return the hostname
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Gets the SSH username for this node.
     *
     * @return the username
     */
    public String getUser() {
        return user;
    }

    /**
     * Gets the SSH port for this node.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the identity file path for this node.
     *
     * @return the identity file path, or null if not set
     */
    public String getIdentityFile() {
        return identityFile;
    }

    @Override
    public String toString() {
        return String.format("Node{hostname='%s', user='%s', port=%d}",
            hostname, user, port);
    }

    /**
     * Represents the result of a command execution.
     */
    public static class CommandResult {
        private final String stdout;
        private final String stderr;
        private final int exitCode;

        public CommandResult(String stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public int getExitCode() {
            return exitCode;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return String.format("CommandResult{exitCode=%d, stdout='%s', stderr='%s'}",
                exitCode, stdout, stderr);
        }
    }
}
