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
import java.io.InputStream;
import java.io.InputStreamReader;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * Represents a single node in the infrastructure as a pure POJO.
 *
 * <p>This is a pure POJO class that provides SSH-based command execution
 * capabilities. It has NO dependency on ActorSystem or workflow components.</p>
 *
 * <h2>Three Levels of Usage</h2>
 *
 * <h3>Level 1: Pure POJO (Synchronous)</h3>
 * <pre>{@code
 * Node node = new Node("192.168.1.1", "admin");
 * CommandResult result = node.executeCommand("show version");
 * System.out.println(result.getStdout());
 * }</pre>
 *
 * <h3>Level 2: Actor-based (Asynchronous, Parallel)</h3>
 * <pre>{@code
 * ActorSystem system = new ActorSystem("iac", 4);
 * ActorRef<Node> nodeActor = system.actorOf("node1", node);
 * CompletableFuture<CommandResult> future = nodeActor.ask(n -> n.executeCommand("show version"));
 * }</pre>
 *
 * <h3>Level 3: Workflow-based (YAML/JSON/XML)</h3>
 * <pre>{@code
 * // Use NodeInterpreter instead for workflow capabilities
 * NodeInterpreter nodeInterpreter = new NodeInterpreter(node, system);
 * IIActorRef<NodeInterpreter> nodeActor = new IIActorRef<>("node1", nodeInterpreter, system);
 * }</pre>
 *
 * <p>Uses ssh-agent for SSH key authentication. Make sure ssh-agent is running
 * and your SSH key is added before using this class.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class Node {

    private final String hostname;
    private final String user;
    private final int port;
    private final boolean localMode;
    private final String password;

    /**
     * Constructs a Node with the specified connection parameters (POJO constructor).
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     */
    public Node(String hostname, String user, int port) {
        this(hostname, user, port, false, null);
    }

    /**
     * Constructs a Node with the specified connection parameters and local mode.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     * @param localMode if true, execute commands locally instead of via SSH
     */
    public Node(String hostname, String user, int port, boolean localMode) {
        this(hostname, user, port, localMode, null);
    }

    /**
     * Constructs a Node with all connection parameters including password.
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     * @param port the SSH port (typically 22)
     * @param localMode if true, execute commands locally instead of via SSH
     * @param password the SSH password (null to use ssh-agent key authentication)
     */
    public Node(String hostname, String user, int port, boolean localMode, String password) {
        this.hostname = hostname;
        this.user = user;
        this.port = port;
        this.localMode = localMode;
        this.password = password;
    }

    /**
     * Constructs a Node with default port 22 (POJO constructor).
     *
     * @param hostname the hostname or IP address of the node
     * @param user the SSH username
     */
    public Node(String hostname, String user) {
        this(hostname, user, 22, false, null);
    }

    /**
     * Checks if this node is in local execution mode.
     *
     * @return true if commands are executed locally, false for SSH
     */
    public boolean isLocalMode() {
        return localMode;
    }

    /**
     * Executes a command on the node.
     *
     * <p>If localMode is true, executes the command locally using ProcessBuilder.
     * Otherwise, executes via SSH using JSch.</p>
     *
     * @param command the command to execute
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if command execution fails
     */
    public CommandResult executeCommand(String command) throws IOException {
        if (localMode) {
            return executeLocalCommand(command);
        }
        return executeRemoteCommand(command);
    }

    /**
     * Executes a command locally using ProcessBuilder with real-time streaming.
     *
     * <p>Output is streamed to System.out/System.err in real-time as it becomes available,
     * while also being captured for the CommandResult.</p>
     *
     * @param command the command to execute
     * @return the execution result
     * @throws IOException if command execution fails
     */
    private CommandResult executeLocalCommand(String command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        Process process = pb.start();

        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        // Read stderr in separate thread to avoid deadlock
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrBuilder) {
                        stderrBuilder.append(line).append("\n");
                    }
                    System.err.println(line);
                }
            } catch (IOException e) {
                // Ignore
            }
        });
        stderrThread.start();

        // Read stdout with real-time streaming
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdoutBuilder.append(line).append("\n");
                System.out.println(line);
            }
        }

        try {
            stderrThread.join();
            int exitCode = process.waitFor();
            return new CommandResult(
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim(),
                exitCode
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        }
    }

    /**
     * Executes a command on the remote node via SSH using JSch with real-time streaming.
     *
     * <p>Output is streamed to System.out/System.err in real-time as it becomes available,
     * while also being captured for the CommandResult.</p>
     *
     * @param command the command to execute
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if SSH connection or command execution fails
     */
    private CommandResult executeRemoteCommand(String command) throws IOException {
        Session session = null;
        ChannelExec channel = null;

        try {
            // Create JSch session
            session = createSession();
            session.connect();

            // Open exec channel
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            // Get streams before connecting
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();

            // Connect channel
            channel.connect();

            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            // Read stderr in separate thread to avoid deadlock
            final InputStream stderrFinal = stderr;
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderrFinal))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderrBuilder) {
                            stderrBuilder.append(line).append("\n");
                        }
                        System.err.println(line);
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });
            stderrThread.start();

            // Read stdout with real-time streaming
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutBuilder.append(line).append("\n");
                    System.out.println(line);
                }
            }

            // Wait for stderr thread
            stderrThread.join();

            // Wait for channel to close
            while (!channel.isClosed()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Command execution interrupted", e);
                }
            }

            int exitCode = channel.getExitStatus();

            return new CommandResult(
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim(),
                exitCode
            );

        } catch (JSchException e) {
            throw new IOException("SSH connection failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command execution interrupted", e);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private static final String SUDO_PASSWORD_ENV = "SUDO_PASSWORD";

    /**
     * Executes a command with sudo privileges on the remote node.
     *
     * <p>Reads the sudo password from the SUDO_PASSWORD environment variable.
     * If the environment variable is not set, throws an IOException.</p>
     *
     * <p>Multi-line scripts are properly handled by wrapping them in bash -c.</p>
     *
     * @param command the command to execute with sudo
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if SSH connection fails or SUDO_PASSWORD is not set
     */
    public CommandResult executeSudoCommand(String command) throws IOException {
        String sudoPassword = System.getenv(SUDO_PASSWORD_ENV);
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            throw new IOException("SUDO_PASSWORD environment variable is not set");
        }

        // Escape single quotes in command for bash -c
        String escapedCommand = command.replace("'", "'\"'\"'");

        // Use sudo -S to read password from stdin, wrap command in bash -c for multi-line support
        String sudoCommand = String.format("echo '%s' | sudo -S bash -c '%s'",
            sudoPassword.replace("'", "'\\''"), escapedCommand);

        return executeCommand(sudoCommand);
    }

    /**
     * Creates a JSch SSH session with configured credentials.
     *
     * @return configured but not yet connected Session
     * @throws JSchException if session creation fails
     * @throws IOException if SSH key file operations fail
     */
    private Session createSession() throws JSchException, IOException {
        JSch jsch = new JSch();

        // Setup authentication
        if (password != null && !password.isEmpty()) {
            // Password authentication - no ssh-agent needed
        } else {
            // SSH key authentication via ssh-agent
            try {
                com.jcraft.jsch.IdentityRepository repo =
                    new com.jcraft.jsch.AgentIdentityRepository(new com.jcraft.jsch.SSHAgentConnector());
                jsch.setIdentityRepository(repo);
            } catch (Exception e) {
                throw new IOException("ssh-agent is not available. Please start ssh-agent and add your SSH key: " +
                    "eval \"$(ssh-agent -s)\" && ssh-add ~/.ssh/your_key\n" +
                    "Or use --ask-pass for password authentication.", e);
            }
        }

        // Load OpenSSH config file (for user/hostname/port only, NOT for IdentityFile)
        com.jcraft.jsch.ConfigRepository configRepository = null;
        try {
            String sshConfigPath = System.getProperty("user.home") + "/.ssh/config";
            java.io.File configFile = new java.io.File(sshConfigPath);
            if (configFile.exists()) {
                com.jcraft.jsch.OpenSSHConfig openSSHConfig =
                    com.jcraft.jsch.OpenSSHConfig.parseFile(sshConfigPath);
                configRepository = openSSHConfig;
                // Do NOT call jsch.setConfigRepository() - it would override ssh-agent with IdentityFile
            }
        } catch (Exception e) {
            // If config loading fails, continue without it
        }

        // Get effective connection parameters from SSH config
        String effectiveUser = user;
        String effectiveHostname = hostname;
        int effectivePort = port;

        if (configRepository != null) {
            com.jcraft.jsch.ConfigRepository.Config config = configRepository.getConfig(hostname);
            if (config != null) {
                // Override with SSH config values if present
                String configUser = config.getUser();
                if (configUser != null) {
                    effectiveUser = configUser;
                }
                String configHostname = config.getHostname();
                if (configHostname != null) {
                    effectiveHostname = configHostname;
                }
                String configPort = config.getValue("Port");
                if (configPort != null) {
                    try {
                        effectivePort = Integer.parseInt(configPort);
                    } catch (NumberFormatException e) {
                        // Keep original port
                    }
                }
            }
        }

        // Create session
        Session session = jsch.getSession(effectiveUser, effectiveHostname, effectivePort);

        // Set password if using password authentication
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        // Disable strict host key checking (for convenience)
        session.setConfig("StrictHostKeyChecking", "no");

        return session;
    }

    /**
     * Cleans up resources used by this Node.
     * With JSch-based implementation, sessions are closed immediately after use,
     * so this method is a no-op but kept for API compatibility.
     */
    public void cleanup() {
        // No cleanup needed with JSch - sessions are closed in executeCommand()
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
