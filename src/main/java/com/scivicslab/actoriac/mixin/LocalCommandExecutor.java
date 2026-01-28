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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import com.scivicslab.actoriac.Node;

/**
 * Command executor that executes commands on the local machine.
 *
 * <p>Used by NodeGroupInterpreter to execute commands locally when running
 * on the control node itself.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class LocalCommandExecutor implements CommandExecutor {

    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    private final String hostname;

    /**
     * Constructs a local command executor.
     */
    public LocalCommandExecutor() {
        String h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "localhost";
        }
        this.hostname = h;
    }

    @Override
    public Node.CommandResult execute(String command) throws IOException {
        return execute(command, null);
    }

    @Override
    public Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Read stdout
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                    if (callback != null) {
                        callback.onStdout(line);
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });

        // Read stderr
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                    if (callback != null) {
                        callback.onStderr(line);
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });

        stdoutThread.start();
        stderrThread.start();

        try {
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Node.CommandResult(stdout.toString(), "Command timed out", -1);
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            int exitCode = process.exitValue();
            return new Node.CommandResult(stdout.toString().trim(), stderr.toString().trim(), exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Node.CommandResult(stdout.toString(), "Interrupted: " + e.getMessage(), -1);
        }
    }

    @Override
    public Node.CommandResult executeSudo(String command) throws IOException {
        return executeSudo(command, null);
    }

    @Override
    public Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException {
        String sudoPassword = System.getenv("SUDO_PASSWORD");
        if (sudoPassword == null || sudoPassword.isEmpty()) {
            throw new IOException("SUDO_PASSWORD environment variable is not set");
        }

        // Use sudo with stdin password
        String sudoCommand = String.format("echo '%s' | sudo -S %s",
            sudoPassword.replace("'", "'\\''"), command);

        return execute(sudoCommand, callback);
    }

    @Override
    public String getIdentifier() {
        return hostname;
    }
}
