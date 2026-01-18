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
import java.util.Comparator;
import java.util.List;
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
 *   <li>{@code db-clear} - Clear (delete) the log database</li>
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
    version = "actor-IaC 2.12.1",
    description = "Infrastructure as Code workflow automation tool.",
    subcommands = {
        RunCLI.class,
        ListWorkflowsCLI.class,
        DescribeCLI.class,
        LogsCLI.class,
        LogServerCLI.class,
        MergeLogsCLI.class,
        DbClearCLI.class
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
        System.out.println("  actor-iac run -w sysinfo/main-collect-sysinfo.yaml -i inventory.ini");
        System.out.println("  actor-iac run -d ./sysinfo -w main-collect-sysinfo.yaml -i inventory.ini");
        System.out.println("  actor-iac list -w sysinfo");
        System.out.println("  actor-iac describe -w sysinfo/main-collect-sysinfo.yaml");
        System.out.println("  actor-iac log-search --db ./logs --list");
        System.out.println("  actor-iac log-serve --db ./logs/shared");
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
    version = "actor-IaC list 2.12.1",
    description = "List workflows in the specified directory."
)
class ListWorkflowsCLI implements Callable<Integer> {

    @Option(
        names = {"-d", "--dir"},
        description = "Base directory. Defaults to current directory.",
        defaultValue = "."
    )
    private File baseDir;

    @Option(
        names = {"-w", "--workflow"},
        required = true,
        description = "Workflow directory path (relative to -d)"
    )
    private String workflowPath;

    @Option(
        names = {"-o", "--output"},
        description = "Output format: table, json, yaml (default: table)",
        defaultValue = "table"
    )
    private String outputFormat;

    @Override
    public Integer call() {
        File workflowDir = new File(baseDir, workflowPath);
        if (!workflowDir.isDirectory()) {
            System.err.println("Not a directory: " + workflowDir);
            return 1;
        }

        List<WorkflowInfo> workflows = scanWorkflowsForDisplay(workflowDir, workflowPath);
        if (workflows.isEmpty()) {
            if ("json".equalsIgnoreCase(outputFormat)) {
                System.out.println("[]");
            } else if ("yaml".equalsIgnoreCase(outputFormat)) {
                System.out.println("workflows: []");
            } else {
                System.out.println("No workflow files found in " + workflowDir.getPath());
            }
            return 0;
        }

        switch (outputFormat.toLowerCase()) {
            case "json" -> printJson(workflows);
            case "yaml" -> printYaml(workflows);
            default -> printTable(workflows, workflowDir.getPath());
        }
        return 0;
    }

    private void printTable(List<WorkflowInfo> workflows, String dirPath) {
        System.out.println("Workflows in " + dirPath + ":");
        System.out.println();
        for (WorkflowInfo wf : workflows) {
            System.out.println(wf.path());
            if (wf.name() != null) {
                System.out.println("  name: " + wf.name());
            }
            if (wf.description() != null) {
                System.out.println("  description: " + wf.description());
            }
            if (wf.note() != null) {
                System.out.println("  note: " + wf.note());
            }
            System.out.println();
        }
    }

    private void printJson(List<WorkflowInfo> workflows) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (WorkflowInfo wf : workflows) {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("path", wf.path());
            if (wf.name() != null) obj.put("name", wf.name());
            if (wf.description() != null) obj.put("description", wf.description());
            if (wf.note() != null) obj.put("note", wf.note());
            arr.put(obj);
        }
        System.out.println(arr.toString(2));
    }

    private void printYaml(List<WorkflowInfo> workflows) {
        System.out.println("workflows:");
        for (WorkflowInfo wf : workflows) {
            System.out.println("  - path: " + wf.path());
            if (wf.name() != null) {
                System.out.println("    name: " + wf.name());
            }
            if (wf.description() != null) {
                // Handle multi-line descriptions
                if (wf.description().contains("\n")) {
                    System.out.println("    description: |");
                    for (String line : wf.description().split("\n")) {
                        System.out.println("      " + line);
                    }
                } else {
                    System.out.println("    description: " + wf.description());
                }
            }
            if (wf.note() != null) {
                System.out.println("    note: " + wf.note());
            }
        }
    }

    private static List<WorkflowInfo> scanWorkflowsForDisplay(File directory, String workflowPath) {
        if (directory == null) {
            return List.of();
        }

        // Non-recursive scan - only files in immediate directory
        try (Stream<Path> paths = Files.list(directory.toPath())) {
            return paths.filter(Files::isRegularFile)
                 .filter(path -> {
                     String name = path.getFileName().toString().toLowerCase();
                     return name.endsWith(".yaml") || name.endsWith(".yml")
                         || name.endsWith(".json") || name.endsWith(".xml");
                 })
                 .map(path -> {
                     File file = path.toFile();
                     String relPath = workflowPath.endsWith("/")
                         ? workflowPath + file.getName()
                         : workflowPath + "/" + file.getName();
                     return extractWorkflowInfo(file, relPath);
                 })
                 .sorted(Comparator.comparing(WorkflowInfo::path, String.CASE_INSENSITIVE_ORDER))
                 .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Failed to scan workflows: " + e.getMessage());
            return List.of();
        }
    }

    private static WorkflowInfo extractWorkflowInfo(File file, String path) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return extractWorkflowInfoFromYaml(file, path);
        } else if (fileName.endsWith(".json")) {
            return extractWorkflowInfoFromJson(file, path);
        }
        return new WorkflowInfo(path, null, null, null);
    }

    private static WorkflowInfo extractWorkflowInfoFromYaml(File file, String path) {
        String name = null;
        String description = null;
        String note = null;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            StringBuilder currentValue = new StringBuilder();
            String currentField = null;
            boolean inMultiLine = false;

            while ((line = reader.readLine()) != null) {
                // Stop at steps or vertices
                if (line.trim().startsWith("steps:") || line.trim().startsWith("vertices:")) {
                    break;
                }

                if (inMultiLine) {
                    if (line.startsWith("  ") || line.startsWith("\t") || line.trim().isEmpty()) {
                        if (!line.trim().isEmpty()) {
                            currentValue.append(line.trim()).append(" ");
                        }
                        continue;
                    } else {
                        // End of multi-line
                        String value = currentValue.toString().trim();
                        if ("name".equals(currentField)) name = value;
                        else if ("description".equals(currentField)) description = value;
                        else if ("note".equals(currentField)) note = value;
                        inMultiLine = false;
                        currentValue = new StringBuilder();
                        currentField = null;
                    }
                }

                if (line.trim().startsWith("name:") && name == null) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.equals("|") || value.equals(">")) {
                        inMultiLine = true;
                        currentField = "name";
                    } else if (!value.isEmpty()) {
                        name = stripQuotes(value);
                    }
                } else if (line.trim().startsWith("description:") && description == null) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.equals("|") || value.equals(">")) {
                        inMultiLine = true;
                        currentField = "description";
                    } else if (!value.isEmpty()) {
                        description = stripQuotes(value);
                    }
                } else if (line.trim().startsWith("note:") && note == null) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.equals("|") || value.equals(">")) {
                        inMultiLine = true;
                        currentField = "note";
                    } else if (!value.isEmpty()) {
                        note = stripQuotes(value);
                    }
                }
            }

            // Handle trailing multi-line
            if (inMultiLine && currentValue.length() > 0) {
                String value = currentValue.toString().trim();
                if ("name".equals(currentField)) name = value;
                else if ("description".equals(currentField)) description = value;
                else if ("note".equals(currentField)) note = value;
            }
        } catch (IOException e) {
            // Ignore
        }
        return new WorkflowInfo(path, name, description, note);
    }

    private static String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static WorkflowInfo extractWorkflowInfoFromJson(File file, String path) {
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            org.json.JSONTokener tokener = new org.json.JSONTokener(reader);
            org.json.JSONObject json = new org.json.JSONObject(tokener);
            return new WorkflowInfo(
                path,
                json.optString("name", null),
                json.optString("description", null),
                json.optString("note", null)
            );
        } catch (Exception e) {
            // Ignore
        }
        return new WorkflowInfo(path, null, null, null);
    }

    private static record WorkflowInfo(String path, String name, String description, String note) {}
}
