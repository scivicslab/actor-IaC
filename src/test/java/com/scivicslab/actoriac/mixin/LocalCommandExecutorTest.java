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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.scivicslab.actoriac.Node;

/**
 * Tests for LocalCommandExecutor.
 *
 * <p>These tests execute actual shell commands on the local machine.
 * They are platform-dependent and may behave differently on different OS.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
@DisplayName("LocalCommandExecutor")
@EnabledOnOs({OS.LINUX, OS.MAC})
public class LocalCommandExecutorTest {

    private LocalCommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LocalCommandExecutor();
    }

    // ========================================================================
    // Basic command execution tests
    // ========================================================================

    @Nested
    @DisplayName("Basic Command Execution")
    class BasicExecution {

        @Test
        @DisplayName("Should execute simple echo command")
        void shouldExecuteSimpleEchoCommand() throws IOException {
            Node.CommandResult result = executor.execute("echo 'Hello World'");

            assertTrue(result.isSuccess(), "echo command should succeed");
            assertEquals("Hello World", result.getStdout().trim());
            assertEquals(0, result.getExitCode());
        }

        @Test
        @DisplayName("Should execute command with pipe")
        void shouldExecuteCommandWithPipe() throws IOException {
            Node.CommandResult result = executor.execute("echo 'line1\nline2\nline3' | wc -l");

            assertTrue(result.isSuccess());
            assertEquals("3", result.getStdout().trim());
        }

        @Test
        @DisplayName("Should capture exit code for failing command")
        void shouldCaptureExitCodeForFailingCommand() throws IOException {
            Node.CommandResult result = executor.execute("exit 42");

            assertFalse(result.isSuccess());
            assertEquals(42, result.getExitCode());
        }

        @Test
        @DisplayName("Should capture stderr")
        void shouldCaptureStderr() throws IOException {
            Node.CommandResult result = executor.execute("echo 'error message' >&2");

            assertTrue(result.isSuccess()); // exit code 0
            assertEquals("error message", result.getStderr().trim());
        }

        @Test
        @DisplayName("Should handle command that writes to both stdout and stderr")
        void shouldHandleBothStdoutAndStderr() throws IOException {
            Node.CommandResult result = executor.execute(
                "echo 'stdout' && echo 'stderr' >&2");

            assertTrue(result.isSuccess());
            assertEquals("stdout", result.getStdout().trim());
            assertEquals("stderr", result.getStderr().trim());
        }
    }

    // ========================================================================
    // Output callback tests
    // ========================================================================

    @Nested
    @DisplayName("Output Callback")
    class OutputCallback {

        @Test
        @DisplayName("Should call callback for each stdout line")
        void shouldCallCallbackForStdoutLines() throws IOException {
            List<String> capturedLines = new ArrayList<>();
            Node.OutputCallback callback = new Node.OutputCallback() {
                @Override
                public void onStdout(String line) {
                    capturedLines.add("stdout: " + line);
                }

                @Override
                public void onStderr(String line) {
                    capturedLines.add("stderr: " + line);
                }
            };

            executor.execute("echo 'line1' && echo 'line2'", callback);

            assertTrue(capturedLines.contains("stdout: line1"));
            assertTrue(capturedLines.contains("stdout: line2"));
        }

        @Test
        @DisplayName("Should call callback for stderr lines")
        void shouldCallCallbackForStderrLines() throws IOException {
            List<String> capturedLines = new ArrayList<>();
            Node.OutputCallback callback = new Node.OutputCallback() {
                @Override
                public void onStdout(String line) {
                    capturedLines.add("stdout: " + line);
                }

                @Override
                public void onStderr(String line) {
                    capturedLines.add("stderr: " + line);
                }
            };

            executor.execute("echo 'error' >&2", callback);

            assertTrue(capturedLines.contains("stderr: error"));
        }

        @Test
        @DisplayName("Should work with null callback")
        void shouldWorkWithNullCallback() throws IOException {
            // Should not throw
            Node.CommandResult result = executor.execute("echo 'test'", null);

            assertTrue(result.isSuccess());
            assertEquals("test", result.getStdout().trim());
        }
    }

    // ========================================================================
    // Identifier tests
    // ========================================================================

    @Nested
    @DisplayName("Identifier")
    class IdentifierTests {

        @Test
        @DisplayName("Should return hostname as identifier")
        void shouldReturnHostnameAsIdentifier() {
            String identifier = executor.getIdentifier();

            assertNotNull(identifier);
            assertFalse(identifier.isEmpty());
            // Should be either the actual hostname or "localhost"
            assertTrue(identifier.equals("localhost") ||
                identifier.matches("[a-zA-Z0-9.-]+"));
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle empty command output")
        void shouldHandleEmptyCommandOutput() throws IOException {
            Node.CommandResult result = executor.execute("true");

            assertTrue(result.isSuccess());
            assertEquals("", result.getStdout().trim());
            assertEquals(0, result.getExitCode());
        }

        @Test
        @DisplayName("Should handle command with special characters")
        void shouldHandleSpecialCharacters() throws IOException {
            Node.CommandResult result = executor.execute("echo 'Hello $USER'");

            assertTrue(result.isSuccess());
            // Single quotes should prevent variable expansion
            assertEquals("Hello $USER", result.getStdout().trim());
        }

        @Test
        @DisplayName("Should handle command with double quotes")
        void shouldHandleDoubleQuotes() throws IOException {
            Node.CommandResult result = executor.execute("echo \"Hello World\"");

            assertTrue(result.isSuccess());
            assertEquals("Hello World", result.getStdout().trim());
        }

        @Test
        @DisplayName("Should handle command that produces multi-line output")
        void shouldHandleMultiLineOutput() throws IOException {
            Node.CommandResult result = executor.execute(
                "echo 'line1' && echo 'line2' && echo 'line3'");

            assertTrue(result.isSuccess());
            String[] lines = result.getStdout().trim().split("\n");
            assertEquals(3, lines.length);
            assertEquals("line1", lines[0]);
            assertEquals("line2", lines[1]);
            assertEquals("line3", lines[2]);
        }

        @Test
        @DisplayName("Should handle non-existent command")
        void shouldHandleNonExistentCommand() throws IOException {
            Node.CommandResult result = executor.execute(
                "this_command_definitely_does_not_exist_12345");

            assertFalse(result.isSuccess());
            assertEquals(127, result.getExitCode()); // Command not found
        }
    }

    // ========================================================================
    // Sudo tests (skipped if SUDO_PASSWORD not set)
    // ========================================================================

    @Nested
    @DisplayName("Sudo Command Execution")
    class SudoExecution {

        @Test
        @DisplayName("Should throw IOException when SUDO_PASSWORD not set")
        void shouldThrowWhenSudoPasswordNotSet() {
            // This test assumes SUDO_PASSWORD is not set in the test environment
            String sudoPassword = System.getenv("SUDO_PASSWORD");

            if (sudoPassword == null || sudoPassword.isEmpty()) {
                assertThrows(IOException.class, () -> {
                    executor.executeSudo("echo test");
                });
            }
            // If SUDO_PASSWORD is set, skip this test
        }
    }
}
