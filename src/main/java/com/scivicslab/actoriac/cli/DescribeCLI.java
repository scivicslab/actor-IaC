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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import com.scivicslab.pojoactor.workflow.kustomize.WorkflowKustomizer;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI subcommand for displaying workflow descriptions.
 *
 * <p>Usage examples:</p>
 * <pre>
 * # Show workflow description only
 * actor-iac describe -d ./workflows -w my-workflow
 *
 * # Show workflow description with step descriptions
 * actor-iac describe -d ./workflows -w my-workflow --steps
 *
 * # With overlay
 * actor-iac describe -d ./workflows -w my-workflow -o ./overlays/env --steps
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.10.0
 */
@Command(
    name = "describe",
    mixinStandardHelpOptions = true,
    version = "actor-IaC describe 2.12.0",
    description = "Display workflow and step descriptions."
)
public class DescribeCLI implements Callable<Integer> {

    @Option(
        names = {"-d", "--dir"},
        description = "Directory containing workflow files (searched recursively)",
        required = true
    )
    private File workflowDir;

    @Option(
        names = {"-w", "--workflow"},
        description = "Name of the workflow to describe (with or without extension)",
        required = true
    )
    private String workflowName;

    @Option(
        names = {"-o", "--overlay"},
        description = "Overlay directory containing overlay-conf.yaml"
    )
    private File overlayDir;

    @Option(
        names = {"--steps", "--notes"},
        description = "Also display note/description of each step"
    )
    private boolean showSteps;

    /** Cache of discovered workflow files: name -> File */
    private final Map<String, File> workflowCache = new HashMap<>();

    @Override
    public Integer call() {
        // Scan workflow directory
        scanWorkflowDirectory(workflowDir);

        // Find the workflow file
        File workflowFile = findWorkflowFile(workflowName);
        if (workflowFile == null) {
            System.err.println("Workflow not found: " + workflowName);
            System.err.println("Available workflows: " + workflowCache.keySet());
            return 1;
        }

        // Load YAML (with overlay if specified)
        Map<String, Object> yaml;
        if (overlayDir != null) {
            yaml = loadYamlWithOverlay(workflowFile);
        } else {
            yaml = loadYamlFile(workflowFile);
        }

        if (yaml == null) {
            System.err.println("Failed to load workflow: " + workflowFile);
            return 1;
        }

        // Print workflow description
        printWorkflowDescription(workflowFile, yaml);

        return 0;
    }

    /**
     * Prints workflow description.
     */
    @SuppressWarnings("unchecked")
    private void printWorkflowDescription(File file, Map<String, Object> yaml) {
        String name = (String) yaml.getOrDefault("name", "(unnamed)");
        String description = (String) yaml.get("description");

        System.out.println("Workflow: " + name);
        System.out.println("File: " + file.getAbsolutePath());
        if (overlayDir != null) {
            System.out.println("Overlay: " + overlayDir.getAbsolutePath());
        }
        System.out.println();

        // Workflow-level description
        System.out.println("Description:");
        if (description != null && !description.isBlank()) {
            for (String line : description.split("\n")) {
                System.out.println("  " + line);
            }
        } else {
            System.out.println("  (no description)");
        }

        // Step descriptions (if --steps flag is set)
        if (showSteps) {
            System.out.println();
            System.out.println("Steps:");

            List<Map<String, Object>> steps = (List<Map<String, Object>>) yaml.get("steps");
            if (steps == null || steps.isEmpty()) {
                System.out.println("  (no steps defined)");
                return;
            }

            for (Map<String, Object> step : steps) {
                List<String> states = (List<String>) step.get("states");
                String label = (String) step.get("label");
                String stepNote = (String) step.get("note");

                String stateTransition = (states != null && states.size() >= 2)
                    ? states.get(0) + " -> " + states.get(1)
                    : "?";

                String displayName = (label != null) ? label : "(unnamed)";

                System.out.println();
                System.out.println("  [" + stateTransition + "] " + displayName);
                if (stepNote != null && !stepNote.isBlank()) {
                    for (String line : stepNote.split("\n")) {
                        System.out.println("    " + line);
                    }
                }
            }
        }
    }

    /**
     * Scans the workflow directory recursively for workflow files.
     */
    private void scanWorkflowDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> {
                     String name = path.getFileName().toString().toLowerCase();
                     return name.endsWith(".yaml") || name.endsWith(".yml")
                         || name.endsWith(".json") || name.endsWith(".xml");
                 })
                 .forEach(path -> {
                     File file = path.toFile();
                     String baseName = getBaseName(file.getName());
                     workflowCache.putIfAbsent(baseName, file);
                     workflowCache.putIfAbsent(file.getName(), file);
                 });
        } catch (Exception e) {
            System.err.println("Warning: Failed to scan directory: " + e.getMessage());
        }
    }

    /**
     * Finds a workflow file by name.
     */
    private File findWorkflowFile(String name) {
        // Try exact match first
        File file = workflowCache.get(name);
        if (file != null) {
            return file;
        }

        // Try with extensions
        for (String ext : new String[]{".yaml", ".yml", ".json", ".xml"}) {
            file = workflowCache.get(name + ext);
            if (file != null) {
                return file;
            }
        }

        return null;
    }

    /**
     * Loads a YAML file.
     */
    private Map<String, Object> loadYamlFile(File file) {
        try (InputStream is = new FileInputStream(file)) {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            return yaml.load(is);
        } catch (Exception e) {
            System.err.println("Failed to load YAML file: " + file + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads a YAML file with overlay applied.
     */
    private Map<String, Object> loadYamlWithOverlay(File workflowFile) {
        try {
            WorkflowKustomizer kustomizer = new WorkflowKustomizer();
            Map<String, Map<String, Object>> workflows = kustomizer.build(overlayDir.toPath());

            String targetName = workflowFile.getName();
            for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
                if (entry.getKey().equals(targetName)) {
                    return entry.getValue();
                }
            }

            // Fall back to raw file
            return loadYamlFile(workflowFile);
        } catch (Exception e) {
            System.err.println("Failed to apply overlay, loading raw file: " + e.getMessage());
            return loadYamlFile(workflowFile);
        }
    }

    /**
     * Gets the base name of a file (without extension).
     */
    private static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }
}
