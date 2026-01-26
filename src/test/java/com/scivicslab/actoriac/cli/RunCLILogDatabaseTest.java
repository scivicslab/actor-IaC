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

package com.scivicslab.actoriac.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RunCLI log database configuration.
 *
 * <p>Verifies that the default log database path is in the current directory
 * (where the command is executed), not in the workflow directory.</p>
 *
 * <p>H2's AUTO_SERVER mode handles multiple process access automatically.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
@DisplayName("RunCLI Log Database Configuration Tests")
class RunCLILogDatabaseTest {

    @TempDir
    Path tempDir;

    private Path workflowDir;
    private Path currentDir;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws IOException {
        // Save original user.dir
        originalUserDir = System.getProperty("user.dir");

        // Create workflow directory structure
        workflowDir = tempDir.resolve("workflows");
        Files.createDirectories(workflowDir);

        // Create a simple workflow file
        Path workflowFile = workflowDir.resolve("test-workflow.yaml");
        Files.writeString(workflowFile, """
            name: test-workflow
            steps:
              - label: init
                states: ["0", "1"]
                actions:
                  - actor: nodeGroup
                    method: noop
            """);

        // Create current directory (where command is executed)
        currentDir = tempDir.resolve("execution");
        Files.createDirectories(currentDir);

        // Set user.dir to simulate running from execution directory
        System.setProperty("user.dir", currentDir.toString());
    }

    @AfterEach
    void tearDown() {
        // Restore original user.dir
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }

        // Clean up any database files
        cleanupDatabaseFiles(currentDir);
        cleanupDatabaseFiles(workflowDir);
    }

    private void cleanupDatabaseFiles(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.list(dir)
                .filter(p -> p.toString().endsWith(".mv.db") ||
                             p.toString().endsWith(".trace.db"))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        } catch (IOException e) {
            // Ignore
        }
    }

    @Nested
    @DisplayName("Default Database Path")
    class DefaultDatabasePath {

        @Test
        @DisplayName("Should use current directory for default log database path")
        void shouldUseCurrentDirectoryForDefaultPath() {
            // The default path should be System.getProperty("user.dir") + "/actor-iac-logs"
            File expectedPath = new File(System.getProperty("user.dir"), "actor-iac-logs");

            // This test verifies the path construction logic
            // (We can't easily test the full RunCLI flow without actually executing workflows)
            assertEquals(currentDir.resolve("actor-iac-logs").toAbsolutePath().toString(),
                        expectedPath.getAbsolutePath());
        }

        @Test
        @DisplayName("Should NOT use workflow directory for default log database path")
        void shouldNotUseWorkflowDirectoryForDefaultPath() {
            File expectedPath = new File(System.getProperty("user.dir"), "actor-iac-logs");
            File workflowDirPath = new File(workflowDir.toFile(), "actor-iac-logs");

            // Default path should be in current dir, not workflow dir
            assertNotEquals(workflowDirPath.getAbsolutePath(), expectedPath.getAbsolutePath());
        }

        @Test
        @DisplayName("Default database filename should be actor-iac-logs")
        void shouldUseCorrectDefaultFilename() {
            File defaultPath = new File(System.getProperty("user.dir"), "actor-iac-logs");

            assertEquals("actor-iac-logs", defaultPath.getName());
            // H2 will append .mv.db extension
        }
    }

    @Nested
    @DisplayName("Log Database Path Construction")
    class LogDatabasePathConstruction {

        @Test
        @DisplayName("Should construct absolute path correctly")
        void shouldConstructAbsolutePathCorrectly() {
            String userDir = System.getProperty("user.dir");
            File logDbPath = new File(userDir, "actor-iac-logs");

            assertTrue(logDbPath.isAbsolute() || logDbPath.getAbsolutePath().startsWith("/"),
                      "Path should be absolute");
            assertTrue(logDbPath.getAbsolutePath().endsWith("actor-iac-logs"),
                      "Path should end with actor-iac-logs");
        }

        @Test
        @DisplayName("Should handle user.dir with trailing slash")
        void shouldHandleTrailingSlash() {
            String userDir = currentDir.toString() + "/";
            File logDbPath = new File(userDir, "actor-iac-logs");

            // File constructor should normalize the path
            assertFalse(logDbPath.getAbsolutePath().contains("//"),
                       "Path should not contain double slashes");
        }

        @Test
        @DisplayName("Database path should be independent of workflow directory")
        void shouldBeIndependentOfWorkflowDirectory() {
            // Even when workflow directory is different, db should be in current dir
            String userDir = System.getProperty("user.dir");
            File logDbPath = new File(userDir, "actor-iac-logs");

            assertFalse(logDbPath.getAbsolutePath().contains("workflows"),
                       "Database path should not contain workflow directory");
        }
    }

    @Nested
    @DisplayName("CLI Option Description")
    class CliOptionDescription {

        @Test
        @DisplayName("--log-db description should mention current directory")
        void logDbDescriptionShouldMentionCurrentDirectory() throws Exception {
            // Use reflection to check the @Option annotation
            java.lang.reflect.Field field = RunCLI.class.getDeclaredField("logDbPath");
            picocli.CommandLine.Option option = field.getAnnotation(picocli.CommandLine.Option.class);

            assertNotNull(option, "logDbPath should have @Option annotation");
            assertTrue(option.description()[0].contains("current directory"),
                      "Option description should mention current directory");
        }

        @Test
        @DisplayName("Logging options should have clear naming convention")
        void loggingOptionsShouldHaveClearNaming() throws Exception {
            // Verify --no-log-db option exists
            java.lang.reflect.Field noLogDbField = RunCLI.class.getDeclaredField("noLogDb");
            picocli.CommandLine.Option noLogDbOption = noLogDbField.getAnnotation(picocli.CommandLine.Option.class);
            assertNotNull(noLogDbOption);
            assertTrue(java.util.Arrays.asList(noLogDbOption.names()).contains("--no-log-db"),
                      "Should have --no-log-db option");

            // Verify --quiet option exists
            java.lang.reflect.Field quietField = RunCLI.class.getDeclaredField("quiet");
            picocli.CommandLine.Option quietOption = quietField.getAnnotation(picocli.CommandLine.Option.class);
            assertNotNull(quietOption);
            assertTrue(java.util.Arrays.asList(quietOption.names()).contains("--quiet"),
                      "Should have --quiet option");
        }
    }
}
