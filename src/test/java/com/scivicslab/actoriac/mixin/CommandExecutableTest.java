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
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.actoriac.Node;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Tests for CommandExecutable mixin interface.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>@Action annotations are present on default methods</li>
 *   <li>Default methods correctly delegate to CommandExecutor</li>
 *   <li>Error handling works correctly</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
@DisplayName("CommandExecutable Mixin Interface")
public class CommandExecutableTest {

    // ========================================================================
    // Mock CommandExecutor for testing
    // ========================================================================

    /**
     * Mock CommandExecutor that returns configurable results.
     */
    static class MockCommandExecutor implements CommandExecutor {

        private String lastCommand = null;
        private Node.CommandResult resultToReturn;
        private IOException exceptionToThrow = null;

        public MockCommandExecutor() {
            this.resultToReturn = new Node.CommandResult("output", "", 0);
        }

        public void setResultToReturn(Node.CommandResult result) {
            this.resultToReturn = result;
        }

        public void setExceptionToThrow(IOException e) {
            this.exceptionToThrow = e;
        }

        public String getLastCommand() {
            return lastCommand;
        }

        @Override
        public Node.CommandResult execute(String command) throws IOException {
            return execute(command, null);
        }

        @Override
        public Node.CommandResult execute(String command, Node.OutputCallback callback) throws IOException {
            this.lastCommand = command;
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return resultToReturn;
        }

        @Override
        public Node.CommandResult executeSudo(String command) throws IOException {
            return executeSudo(command, null);
        }

        @Override
        public Node.CommandResult executeSudo(String command, Node.OutputCallback callback) throws IOException {
            this.lastCommand = "sudo: " + command;
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return resultToReturn;
        }

        @Override
        public String getIdentifier() {
            return "mock-executor";
        }
    }

    /**
     * Test implementation of CommandExecutable.
     */
    static class TestCommandExecutable implements CommandExecutable {

        private final MockCommandExecutor executor = new MockCommandExecutor();

        @Override
        public CommandExecutor getCommandExecutor() {
            return executor;
        }

        public MockCommandExecutor getMockExecutor() {
            return executor;
        }
    }

    // ========================================================================
    // Tests for @Action annotations
    // ========================================================================

    @Nested
    @DisplayName("@Action Annotations")
    class ActionAnnotations {

        @Test
        @DisplayName("doExecuteCommand should have @Action(\"executeCommand\")")
        void doExecuteCommandShouldHaveAnnotation() throws NoSuchMethodException {
            Method method = CommandExecutable.class.getMethod("doExecuteCommand", String.class);
            Action action = method.getAnnotation(Action.class);

            assertNotNull(action, "@Action annotation should be present");
            assertEquals("executeCommand", action.value(),
                "Action name should be 'executeCommand'");
        }

        @Test
        @DisplayName("doExecuteSudoCommand should have @Action(\"executeSudoCommand\")")
        void doExecuteSudoCommandShouldHaveAnnotation() throws NoSuchMethodException {
            Method method = CommandExecutable.class.getMethod("doExecuteSudoCommand", String.class);
            Action action = method.getAnnotation(Action.class);

            assertNotNull(action, "@Action annotation should be present");
            assertEquals("executeSudoCommand", action.value(),
                "Action name should be 'executeSudoCommand'");
        }
    }

    // ========================================================================
    // Tests for executeCommand
    // ========================================================================

    @Nested
    @DisplayName("executeCommand (doExecuteCommand)")
    class ExecuteCommandTests {

        @Test
        @DisplayName("Should execute command and return success")
        void shouldExecuteCommandAndReturnSuccess() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setResultToReturn(
                new Node.CommandResult("Hello World", "", 0));

            ActionResult result = executable.doExecuteCommand("[\"echo Hello World\"]");

            assertTrue(result.isSuccess());
            assertEquals("Hello World", result.getResult());
            assertEquals("echo Hello World", executable.getMockExecutor().getLastCommand());
        }

        @Test
        @DisplayName("Should handle command with spaces in arguments")
        void shouldHandleCommandWithSpaces() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setResultToReturn(
                new Node.CommandResult("file1.txt\nfile2.txt", "", 0));

            ActionResult result = executable.doExecuteCommand("[\"ls -la /home/user\"]");

            assertTrue(result.isSuccess());
            assertEquals("ls -la /home/user", executable.getMockExecutor().getLastCommand());
        }

        @Test
        @DisplayName("Should return failure when command fails")
        void shouldReturnFailureWhenCommandFails() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setResultToReturn(
                new Node.CommandResult("", "command not found", 127));

            ActionResult result = executable.doExecuteCommand("[\"nonexistent_command\"]");

            // Note: CommandResult with exit code 127 is considered failure
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should return failure when executor throws IOException")
        void shouldReturnFailureOnIOException() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setExceptionToThrow(
                new IOException("Connection refused"));

            ActionResult result = executable.doExecuteCommand("[\"echo test\"]");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Connection refused"));
        }

        @Test
        @DisplayName("Should include stderr in result when present")
        void shouldIncludeStderrInResult() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setResultToReturn(
                new Node.CommandResult("stdout output", "stderr warning", 0));

            ActionResult result = executable.doExecuteCommand("[\"some_command\"]");

            assertTrue(result.isSuccess());
            assertTrue(result.getResult().contains("stdout output"));
            assertTrue(result.getResult().contains("stderr warning"));
        }
    }

    // ========================================================================
    // Tests for executeSudoCommand
    // ========================================================================

    @Nested
    @DisplayName("executeSudoCommand (doExecuteSudoCommand)")
    class ExecuteSudoCommandTests {

        @Test
        @DisplayName("Should execute sudo command and return success")
        void shouldExecuteSudoCommandAndReturnSuccess() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setResultToReturn(
                new Node.CommandResult("package installed", "", 0));

            ActionResult result = executable.doExecuteSudoCommand("[\"apt-get install vim\"]");

            assertTrue(result.isSuccess());
            assertEquals("package installed", result.getResult());
            assertEquals("sudo: apt-get install vim", executable.getMockExecutor().getLastCommand());
        }

        @Test
        @DisplayName("Should return failure when SUDO_PASSWORD not set")
        void shouldReturnFailureWhenSudoPasswordNotSet() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setExceptionToThrow(
                new IOException("SUDO_PASSWORD environment variable is not set"));

            ActionResult result = executable.doExecuteSudoCommand("[\"apt update\"]");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("SUDO_PASSWORD"));
        }
    }

    // ========================================================================
    // Tests for argument parsing
    // ========================================================================

    @Nested
    @DisplayName("Argument Parsing")
    class ArgumentParsing {

        @Test
        @DisplayName("Should parse JSON array format")
        void shouldParseJsonArrayFormat() {
            TestCommandExecutable executable = new TestCommandExecutable();
            executable.getMockExecutor().setResultToReturn(
                new Node.CommandResult("ok", "", 0));

            executable.doExecuteCommand("[\"echo hello\"]");

            assertEquals("echo hello", executable.getMockExecutor().getLastCommand());
        }

        @Test
        @DisplayName("Should handle empty arguments gracefully")
        void shouldHandleEmptyArgumentsGracefully() {
            TestCommandExecutable executable = new TestCommandExecutable();

            // Empty array should fail
            assertThrows(IllegalArgumentException.class, () -> {
                executable.doExecuteCommand("[]");
            });
        }

        @Test
        @DisplayName("Should handle malformed JSON")
        void shouldHandleMalformedJson() {
            TestCommandExecutable executable = new TestCommandExecutable();

            assertThrows(IllegalArgumentException.class, () -> {
                executable.doExecuteCommand("not valid json");
            });
        }
    }
}
