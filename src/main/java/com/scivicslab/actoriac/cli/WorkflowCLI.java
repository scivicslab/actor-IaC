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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.LogManager;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Main command-line interface for actor-IaC.
 *
 * <p>This is the entry point for all actor-IaC commands. Use subcommands to
 * execute specific operations:</p>
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code run} - Execute workflows</li>
 *   <li>{@code list} - List available workflows</li>
 *   <li>{@code describe} - Describe workflow structure</li>
 *   <li>{@code logs} - Query execution logs</li>
 *   <li>{@code log-server} - Start H2 log server</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>
 * actor-iac run -d ./workflows -w deploy
 * actor-iac list -d ./workflows
 * actor-iac logs --db ./logs --list
 * actor-iac log-server --db ./logs
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.7.0
 */
@Command(
    name = "actor-iac",
    mixinStandardHelpOptions = true,
    version = "actor-IaC 2.10.0",
    description = "Infrastructure as Code workflow automation tool.",
    subcommands = {
        RunCLI.class,
        ListWorkflowsCLI.class,
        DescribeCLI.class,
        LogsCLI.class,
        LogServerCLI.class,
        MergeLogsCLI.class
    }
)
public class WorkflowCLI implements Callable<Integer> {

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        // Load logging configuration from resources
        try (InputStream is = WorkflowCLI.class.getResourceAsStream("/logging.properties")) {
            if (is != null) {
                LogManager.getLogManager().readConfiguration(is);
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to load logging.properties: " + e.getMessage());
        }

        int exitCode = new CommandLine(new WorkflowCLI()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Called when no subcommand is specified.
     * Shows help message.
     */
    @Override
    public Integer call() {
        System.out.println("actor-IaC - Infrastructure as Code workflow automation tool");
        System.out.println();
        System.out.println("Usage: actor-iac <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  run          Execute a workflow");
        System.out.println("  list         List available workflows");
        System.out.println("  describe     Describe workflow structure");
        System.out.println("  log-search   Search execution logs");
        System.out.println("  log-serve    Start H2 log server for centralized logging");
        System.out.println("  log-merge    Merge scattered log databases into one");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  actor-iac run -d ./workflows -w deploy");
        System.out.println("  actor-iac run -d ./workflows -w deploy -i inventory.ini -g webservers");
        System.out.println("  actor-iac list -d ./workflows");
        System.out.println("  actor-iac log-search --db ./logs --list");
        System.out.println("  actor-iac log-serve --db ./logs/shared");
        System.out.println("  actor-iac log-merge --scan ./workflows --target ./logs/merged");
        System.out.println();
        System.out.println("Use 'actor-iac <command> --help' for more information about a command.");
        return 0;
    }
}

/**
 * Subcommand to list workflows discovered under a directory.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    version = "actor-IaC list 2.10.0",
    description = "List workflows discovered under --dir."
)
class ListWorkflowsCLI implements Callable<Integer> {

    @Option(
        names = {"-d", "--dir"},
        required = true,
        description = "Directory containing workflow files (searched recursively)"
    )
    private File workflowDir;

    @Override
    public Integer call() {
        if (!workflowDir.isDirectory()) {
            System.err.println("Not a directory: " + workflowDir);
            return 1;
        }

        List<WorkflowDisplay> displays = scanWorkflowsForDisplay(workflowDir);
        if (displays.isEmpty()) {
            System.out.println("No workflow files found under " + workflowDir.getAbsolutePath());
            return 0;
        }

        System.out.println("Available workflows (directory: " + workflowDir.getAbsolutePath() + ")");
        System.out.println("-".repeat(90));
        System.out.printf("%-4s %-25s %-35s %s%n", "#", "File (-w)", "Path", "Workflow Name (in logs)");
        System.out.println("-".repeat(90));
        int index = 1;
        for (WorkflowDisplay display : displays) {
            String workflowName = display.workflowName() != null ? display.workflowName() : "(no name)";
            System.out.printf("%2d.  %-25s %-35s %s%n",
                index++, display.baseName(), display.relativePath(), workflowName);
        }
        System.out.println("-".repeat(90));
        System.out.println("Use 'actor-iac run -d " + workflowDir + " -w <File>' to execute a workflow.");
        return 0;
    }

    private static List<WorkflowDisplay> scanWorkflowsForDisplay(File directory) {
        if (directory == null) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            Map<String, File> uniqueFiles = new LinkedHashMap<>();
            paths.filter(Files::isRegularFile)
                 .filter(path -> {
                     String name = path.getFileName().toString().toLowerCase();
                     return name.endsWith(".yaml") || name.endsWith(".yml")
                         || name.endsWith(".json") || name.endsWith(".xml");
                 })
                 .forEach(path -> uniqueFiles.putIfAbsent(path.toFile().getAbsolutePath(), path.toFile()));

            Path basePath = directory.toPath();
            return uniqueFiles.values().stream()
                .map(file -> new WorkflowDisplay(
                    getBaseName(file.getName()),
                    relativize(basePath, file.toPath()),
                    extractWorkflowName(file)))
                .sorted(Comparator.comparing(WorkflowDisplay::baseName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Failed to scan workflows: " + e.getMessage());
            return List.of();
        }
    }

    private static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    private static String relativize(Path base, Path target) {
        try {
            return base.relativize(target).toString();
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString();
        }
    }

    private static String extractWorkflowName(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return extractNameFromYaml(file);
        } else if (fileName.endsWith(".json")) {
            return extractNameFromJson(file);
        }
        return null;
    }

    private static String extractNameFromYaml(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("name:")) {
                    String value = line.substring(5).trim();
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value.isEmpty() ? null : value;
                }
                if (line.startsWith("steps:") || line.startsWith("vertices:")) {
                    break;
                }
            }
        } catch (IOException e) {
            // Ignore
        }
        return null;
    }

    private static String extractNameFromJson(File file) {
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            org.json.JSONTokener tokener = new org.json.JSONTokener(reader);
            org.json.JSONObject json = new org.json.JSONObject(tokener);
            return json.optString("name", null);
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static record WorkflowDisplay(String baseName, String relativePath, String workflowName) {}
}
