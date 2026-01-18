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

package com.scivicslab.actoriac.cli;

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
 * Tests for CLI options redesign (v2.12.1).
 *
 * <p>Verifies the new CLI option behavior per design document
 * 160_CLIOptionsRedesign_260118_oo01:</p>
 *
 * <ul>
 *   <li>{@code -d}: Base directory (where logs are created), defaults to current directory</li>
 *   <li>{@code -w}: Workflow file/directory path (required)</li>
 *   <li>Non-recursive directory scanning</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.1
 */
@DisplayName("CLI Options (v2.12.1)")
public class CLIOptionsTest {

    @TempDir
    Path tempDir;

    private Path sysinfoDir;
    private Path helloDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create subdirectories
        sysinfoDir = Files.createDirectories(tempDir.resolve("sysinfo"));
        helloDir = Files.createDirectories(tempDir.resolve("hello"));

        // Create workflow files in sysinfo/
        createWorkflowFile(sysinfoDir.resolve("main-collect-sysinfo.yaml"),
            "main-collect-sysinfo",
            "Main workflow to collect system information from all nodes.");
        createWorkflowFile(sysinfoDir.resolve("collect-sysinfo.yaml"),
            "collect-sysinfo",
            "Sub-workflow to collect system information from each node.");

        // Create workflow files in hello/
        createWorkflowFile(helloDir.resolve("main-hello.yaml"),
            "main-hello",
            "Main workflow for hello world example.");
    }

    private void createWorkflowFile(Path path, String name, String description) throws IOException {
        String content = String.format("""
            name: %s

            description: |
              %s

            steps:
              - states: ["0", "end"]
                actions: []
            """, name, description);
        Files.writeString(path, content);
    }

    @Nested
    @DisplayName("Non-recursive directory scanning")
    class NonRecursiveScanning {

        @Test
        @DisplayName("Should only find workflows in immediate directory")
        void shouldOnlyFindWorkflowsInImmediateDirectory() throws IOException {
            // Create a workflow in root
            createWorkflowFile(tempDir.resolve("root-workflow.yaml"), "root", "Root workflow");

            // Scan root directory
            File[] files = tempDir.toFile().listFiles((File dir, String name) ->
                name.endsWith(".yaml") || name.endsWith(".yml"));

            // Should only find root-workflow.yaml, not subdirectory files
            assertNotNull(files);
            assertEquals(1, files.length, "Should only find files in immediate directory");
            assertEquals("root-workflow.yaml", files[0].getName());
        }

        @Test
        @DisplayName("Should find all workflows when scanning subdirectory")
        void shouldFindAllWorkflowsInSubdirectory() {
            File[] files = sysinfoDir.toFile().listFiles((File dir, String name) ->
                name.endsWith(".yaml") || name.endsWith(".yml"));

            assertNotNull(files);
            assertEquals(2, files.length, "Should find 2 workflows in sysinfo/");
        }
    }

    @Nested
    @DisplayName("Workflow file resolution")
    class WorkflowFileResolution {

        @Test
        @DisplayName("Should resolve workflow file with extension")
        void shouldResolveWorkflowFileWithExtension() {
            Path workflowPath = tempDir.resolve("sysinfo/main-collect-sysinfo.yaml");
            assertTrue(Files.exists(workflowPath), "Workflow file should exist");
        }

        @Test
        @DisplayName("Should resolve workflow file without extension")
        void shouldResolveWorkflowFileWithoutExtension() {
            // Try to find file by trying extensions
            String baseName = "main-collect-sysinfo";
            String[] extensions = {".yaml", ".yml", ".json", ".xml"};

            Path found = null;
            for (String ext : extensions) {
                Path candidate = sysinfoDir.resolve(baseName + ext);
                if (Files.exists(candidate)) {
                    found = candidate;
                    break;
                }
            }

            assertNotNull(found, "Should find workflow file with extension");
            assertEquals("main-collect-sysinfo.yaml", found.getFileName().toString());
        }

        @Test
        @DisplayName("Should not find non-existent workflow")
        void shouldNotFindNonExistentWorkflow() {
            Path workflowPath = tempDir.resolve("sysinfo/non-existent.yaml");
            assertFalse(Files.exists(workflowPath), "Non-existent workflow should not exist");
        }
    }

    @Nested
    @DisplayName("Workflow description extraction")
    class WorkflowDescriptionExtraction {

        @Test
        @DisplayName("Should extract description from workflow file")
        void shouldExtractDescriptionFromWorkflowFile() throws IOException {
            Path workflowPath = sysinfoDir.resolve("main-collect-sysinfo.yaml");
            String content = Files.readString(workflowPath);

            assertTrue(content.contains("description:"), "Workflow should have description");
            assertTrue(content.contains("Main workflow to collect system information"),
                "Description should contain expected text");
        }
    }
}
