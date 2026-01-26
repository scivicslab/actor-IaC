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
 * @author devteam@scivicslab.com
 */
public class Node {

    private final String hostname;
    private final String user;
    private final int port;
    private final boolean localMode;
    private final String password;

    // Jump host session (kept open for the duration of the connection)
    private Session jumpHostSession = null;

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
        return executeCommand(command, null);
    }

    /**
     * Executes a command on the node with real-time output callback.
     *
     * <p>If localMode is true, executes the command locally using ProcessBuilder.
     * Otherwise, executes via SSH using JSch.</p>
     *
     * <p>The callback receives stdout and stderr lines as they are produced,
     * enabling real-time forwarding to accumulators.</p>
     *
     * @param command the command to execute
     * @param callback the callback for real-time output (may be null)
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if command execution fails
     */
    public CommandResult executeCommand(String command, OutputCallback callback) throws IOException {
        if (localMode) {
            return executeLocalCommand(command, callback);
        }
        return executeRemoteCommand(command, callback);
    }

    /**
     * Executes a command locally using ProcessBuilder with real-time streaming.
     *
     * <p>Output is streamed via callback (if provided) in real-time as it becomes available,
     * while also being captured for the CommandResult.</p>
     *
     * @param command the command to execute
     * @param callback the callback for real-time output (may be null)
     * @return the execution result
     * @throws IOException if command execution fails
     */
    private CommandResult executeLocalCommand(String command, OutputCallback callback) throws IOException {
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
                    if (callback != null) {
                        callback.onStderr(line);
                    }
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
                if (callback != null) {
                    callback.onStdout(line);
                }
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
     * <p>Output is streamed via callback (if provided) in real-time as it becomes available,
     * while also being captured for the CommandResult.</p>
     *
     * @param command the command to execute
     * @param callback the callback for real-time output (may be null)
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if SSH connection or command execution fails
     */
    private CommandResult executeRemoteCommand(String command, OutputCallback callback) throws IOException {
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
                        if (callback != null) {
                            callback.onStderr(line);
                        }
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
                    if (callback != null) {
                        callback.onStdout(line);
                    }
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
            String message = e.getMessage();
            if (message != null && message.contains("USERAUTH fail")) {
                throw new IOException(String.format(
                    "SSH authentication failed for %s@%s:%d.%n" +
                    "%n" +
                    "[~/.ssh/id_ed25519 or ~/.ssh/id_rsa]%n" +
                    "  ssh-add || { eval \"$(ssh-agent -s)\" && ssh-add; }%n" +
                    "%n" +
                    "[Custom key, e.g. ~/.ssh/mykey]%n" +
                    "  ssh-add ~/.ssh/mykey || { eval \"$(ssh-agent -s)\" && ssh-add ~/.ssh/mykey; }%n" +
                    "%n" +
                    "Test: ssh %s@%s echo OK",
                    user, hostname, port, user, hostname), e);
            } else if (message != null && message.contains("Auth fail")) {
                throw new IOException(String.format(
                    "SSH authentication failed for %s@%s:%d.%n" +
                    "%n" +
                    "[~/.ssh/id_ed25519 or ~/.ssh/id_rsa]%n" +
                    "  ssh-add || { eval \"$(ssh-agent -s)\" && ssh-add; }%n" +
                    "%n" +
                    "[Custom key, e.g. ~/.ssh/mykey]%n" +
                    "  ssh-add ~/.ssh/mykey || { eval \"$(ssh-agent -s)\" && ssh-add ~/.ssh/mykey; }%n" +
                    "%n" +
                    "Test: ssh %s@%s echo OK",
                    user, hostname, port, user, hostname), e);
            } else if (message != null && (message.contains("Connection refused") || message.contains("connect timed out"))) {
                throw new IOException(String.format(
                    "SSH connection failed to %s:%d - %s. " +
                    "Verify the host is reachable and SSH service is running.",
                    hostname, port, message), e);
            } else if (message != null && message.contains("UnknownHostException")) {
                throw new IOException(String.format(
                    "SSH connection failed: Unknown host '%s'. " +
                    "Check the hostname or IP address in inventory.",
                    hostname), e);
            }
            throw new IOException("SSH connection failed to " + hostname + ": " + message, e);
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
            // Clean up jump host session if used
            if (jumpHostSession != null && jumpHostSession.isConnected()) {
                jumpHostSession.disconnect();
                jumpHostSession = null;
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
        return executeSudoCommand(command, null);
    }

    /**
     * Executes a command with sudo privileges on the remote node with real-time output callback.
     *
     * <p>Reads the sudo password from the SUDO_PASSWORD environment variable.
     * If the environment variable is not set, throws an IOException.</p>
     *
     * <p>Multi-line scripts are properly handled by wrapping them in bash -c.</p>
     *
     * @param command the command to execute with sudo
     * @param callback the callback for real-time output (may be null)
     * @return the execution result containing stdout, stderr, and exit code
     * @throws IOException if SSH connection fails or SUDO_PASSWORD is not set
     */
    public CommandResult executeSudoCommand(String command, OutputCallback callback) throws IOException {
        String sudoPassword = System.getenv(SUDO_PASSWORD_ENV);
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            throw new IOException("SUDO_PASSWORD environment variable is not set");
        }

        // Escape single quotes in command for bash -c
        String escapedCommand = command.replace("'", "'\"'\"'");

        // Use sudo -S to read password from stdin, wrap command in bash -c for multi-line support
        String sudoCommand = String.format("echo '%s' | sudo -S bash -c '%s'",
            sudoPassword.replace("'", "'\\''"), escapedCommand);

        return executeCommand(sudoCommand, callback);
    }

    /**
     * Creates a JSch SSH session with configured credentials.
     * Supports ProxyJump for connections through a jump host.
     *
     * @return configured but not yet connected Session
     * @throws JSchException if session creation fails
     * @throws IOException if SSH key file operations fail
     */
    private Session createSession() throws JSchException, IOException {
        JSch jsch = new JSch();

        // Load OpenSSH config file first (for user/hostname/port/IdentityFile/ProxyJump)
        com.jcraft.jsch.ConfigRepository configRepository = null;
        String identityFileFromConfig = null;
        String proxyJump = null;
        try {
            String sshConfigPath = System.getProperty("user.home") + "/.ssh/config";
            java.io.File configFile = new java.io.File(sshConfigPath);
            if (configFile.exists()) {
                com.jcraft.jsch.OpenSSHConfig openSSHConfig =
                    com.jcraft.jsch.OpenSSHConfig.parseFile(sshConfigPath);
                configRepository = openSSHConfig;

                // Get IdentityFile and ProxyJump from config for this host
                com.jcraft.jsch.ConfigRepository.Config hostConfig = openSSHConfig.getConfig(hostname);
                if (hostConfig != null) {
                    identityFileFromConfig = hostConfig.getValue("IdentityFile");
                    // Expand ~ to home directory
                    if (identityFileFromConfig != null && identityFileFromConfig.startsWith("~")) {
                        identityFileFromConfig = System.getProperty("user.home") +
                            identityFileFromConfig.substring(1);
                    }
                    // Get ProxyJump setting
                    proxyJump = hostConfig.getValue("ProxyJump");
                }
            }
        } catch (Exception e) {
            // If config loading fails, continue without it
        }

        // Setup authentication
        setupAuthentication(jsch, identityFileFromConfig);

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

        Session session;

        // Handle ProxyJump if configured
        if (proxyJump != null && !proxyJump.isEmpty()) {
            session = createSessionViaProxyJump(jsch, proxyJump, effectiveUser, effectiveHostname, effectivePort);
        } else {
            // Direct connection
            session = jsch.getSession(effectiveUser, effectiveHostname, effectivePort);
        }

        // Set password if using password authentication
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        // Disable strict host key checking (for convenience)
        session.setConfig("StrictHostKeyChecking", "no");

        return session;
    }

    /**
     * Sets up authentication for JSch.
     */
    private void setupAuthentication(JSch jsch, String identityFileFromConfig) throws IOException, JSchException {
        if (password != null && !password.isEmpty()) {
            // Password authentication - no ssh-agent needed
            return;
        }

        boolean authConfigured = false;

        // Priority 1: Try ssh-agent first (supports Ed25519 and other modern key types)
        try {
            com.jcraft.jsch.IdentityRepository repo =
                new com.jcraft.jsch.AgentIdentityRepository(new com.jcraft.jsch.SSHAgentConnector());
            jsch.setIdentityRepository(repo);
            authConfigured = true;
        } catch (Exception e) {
            // ssh-agent not available, will try key files directly
        }

        // Priority 2: Use IdentityFile from ~/.ssh/config (for RSA/ECDSA keys without passphrase)
        if (!authConfigured && identityFileFromConfig != null) {
            java.io.File keyFile = new java.io.File(identityFileFromConfig);
            if (keyFile.exists() && keyFile.canRead()) {
                try {
                    jsch.addIdentity(identityFileFromConfig);
                    authConfigured = true;
                } catch (JSchException ex) {
                    // Key file may require passphrase or be unsupported type
                }
            }
        }

        // Priority 3: Fallback to default key files (for RSA/ECDSA keys without passphrase)
        if (!authConfigured) {
            String home = System.getProperty("user.home");
            String[] keyFiles = {
                home + "/.ssh/id_rsa",
                home + "/.ssh/id_ecdsa",
                home + "/.ssh/id_dsa"
                // Note: id_ed25519 requires ssh-agent, so not included here
            };

            for (String keyFile : keyFiles) {
                java.io.File f = new java.io.File(keyFile);
                if (f.exists() && f.canRead()) {
                    try {
                        jsch.addIdentity(keyFile);
                        authConfigured = true;
                        break;
                    } catch (JSchException ex) {
                        // Key file may require passphrase, try next
                    }
                }
            }

            if (!authConfigured) {
                throw new IOException("SSH authentication failed: No usable authentication method found.\n" +
                    "\n" +
                    "[~/.ssh/id_ed25519 or ~/.ssh/id_rsa]\n" +
                    "  ssh-add || { eval \"$(ssh-agent -s)\" && ssh-add; }\n" +
                    "\n" +
                    "[Custom key, e.g. ~/.ssh/mykey]\n" +
                    "  ssh-add ~/.ssh/mykey || { eval \"$(ssh-agent -s)\" && ssh-add ~/.ssh/mykey; }\n" +
                    "\n" +
                    "[Password authentication]\n" +
                    "  Use --ask-pass option");
            }
        }
    }

    /**
     * Creates a session through a jump host using ProxyJump.
     * Format: user@host or user@host:port
     */
    private Session createSessionViaProxyJump(JSch jsch, String proxyJump,
            String targetUser, String targetHost, int targetPort) throws JSchException, IOException {

        // Parse ProxyJump: user@host or user@host:port
        String jumpUser;
        String jumpHost;
        int jumpPort = 22;

        String[] atParts = proxyJump.split("@", 2);
        if (atParts.length == 2) {
            jumpUser = atParts[0];
            String hostPart = atParts[1];
            if (hostPart.contains(":")) {
                String[] hostPortParts = hostPart.split(":", 2);
                jumpHost = hostPortParts[0];
                try {
                    jumpPort = Integer.parseInt(hostPortParts[1]);
                } catch (NumberFormatException e) {
                    jumpHost = hostPart;
                }
            } else {
                jumpHost = hostPart;
            }
        } else {
            // No user specified, use current user
            jumpUser = user;
            String hostPart = proxyJump;
            if (hostPart.contains(":")) {
                String[] hostPortParts = hostPart.split(":", 2);
                jumpHost = hostPortParts[0];
                try {
                    jumpPort = Integer.parseInt(hostPortParts[1]);
                } catch (NumberFormatException e) {
                    jumpHost = hostPart;
                }
            } else {
                jumpHost = hostPart;
            }
        }

        // Create and connect to jump host
        jumpHostSession = jsch.getSession(jumpUser, jumpHost, jumpPort);
        jumpHostSession.setConfig("StrictHostKeyChecking", "no");
        if (password != null && !password.isEmpty()) {
            jumpHostSession.setPassword(password);
        }
        jumpHostSession.connect();

        // Set up port forwarding through jump host
        // Find an available local port
        int localPort = jumpHostSession.setPortForwardingL(0, targetHost, targetPort);

        // Create session to target via the forwarded port
        Session targetSession = jsch.getSession(targetUser, "127.0.0.1", localPort);

        return targetSession;
    }

    /**
     * Cleans up resources used by this Node.
     * Closes any open jump host sessions.
     */
    public void cleanup() {
        if (jumpHostSession != null && jumpHostSession.isConnected()) {
            jumpHostSession.disconnect();
            jumpHostSession = null;
        }
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
     * Callback interface for real-time output streaming.
     *
     * <p>Implementations receive stdout and stderr lines as they are produced,
     * enabling real-time forwarding to accumulators without blocking.</p>
     */
    public interface OutputCallback {
        /**
         * Called when a stdout line is read.
         *
         * @param line the stdout line (without newline)
         */
        void onStdout(String line);

        /**
         * Called when a stderr line is read.
         *
         * @param line the stderr line (without newline)
         */
        void onStderr(String line);
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
