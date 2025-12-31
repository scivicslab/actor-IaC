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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.InterpreterIIAR;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command-line interface for executing actor-IaC workflows.
 *
 * <p>This CLI tool allows users to execute workflows defined in YAML, JSON, or XML
 * format. It supports sub-workflows by recursively searching for workflow files
 * in the specified directory.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar actor-IaC.jar -d /path/to/workflows -w main-workflow.yaml
 * java -jar actor-IaC.jar --dir ./workflows --workflow deploy --threads 8
 * </pre>
 *
 * <h2>Options</h2>
 * <ul>
 *   <li>{@code -d, --dir} - Directory containing workflow files (searched recursively)</li>
 *   <li>{@code -w, --workflow} - Name of the main workflow to execute (with or without extension)</li>
 *   <li>{@code -t, --threads} - Number of worker threads for CPU-bound operations (default: 4)</li>
 *   <li>{@code -v, --verbose} - Enable verbose output</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.7.0
 */
@Command(
    name = "actor-iac",
    mixinStandardHelpOptions = true,
    version = "actor-IaC 2.7.0",
    description = "Execute actor-IaC workflows defined in YAML, JSON, or XML format."
)
public class WorkflowCLI implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(WorkflowCLI.class.getName());

    @Option(
        names = {"-d", "--dir"},
        description = "Directory containing workflow files (searched recursively)",
        required = true
    )
    private File workflowDir;

    @Option(
        names = {"-w", "--workflow"},
        description = "Name of the main workflow to execute (with or without extension)",
        required = true
    )
    private String workflowName;

    @Option(
        names = {"-t", "--threads"},
        description = "Number of worker threads for CPU-bound operations (default: ${DEFAULT-VALUE})",
        defaultValue = "4"
    )
    private int threads;

    @Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose output"
    )
    private boolean verbose;

    /** Cache of discovered workflow files: name -> File */
    private final Map<String, File> workflowCache = new HashMap<>();

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new WorkflowCLI()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Executes the workflow.
     *
     * @return exit code (0 for success, non-zero for failure)
     */
    @Override
    public Integer call() {
        // Validate workflow directory
        if (!workflowDir.exists()) {
            LOG.severe("Workflow directory does not exist: " + workflowDir);
            return 1;
        }
        if (!workflowDir.isDirectory()) {
            LOG.severe("Not a directory: " + workflowDir);
            return 1;
        }

        // Scan workflow directory recursively
        LOG.info("Scanning workflow directory: " + workflowDir.getAbsolutePath());
        scanWorkflowDirectory(workflowDir.toPath());

        if (verbose) {
            LOG.info("Discovered " + workflowCache.size() + " workflow files:");
            workflowCache.forEach((name, file) ->
                LOG.info("  " + name + " -> " + file.getPath()));
        }

        // Find main workflow
        File mainWorkflowFile = findWorkflowFile(workflowName);
        if (mainWorkflowFile == null) {
            LOG.severe("Workflow not found: " + workflowName);
            LOG.info("Available workflows: " + workflowCache.keySet());
            return 1;
        }

        LOG.info("=== actor-IaC Workflow CLI ===");
        LOG.info("Workflow directory: " + workflowDir.getAbsolutePath());
        LOG.info("Main workflow: " + mainWorkflowFile.getName());
        LOG.info("Worker threads: " + threads);

        // Create actor system
        IIActorSystem system = new IIActorSystem("actor-iac-cli", threads);

        try {
            // Create sub-workflow caller that uses file system
            FileBasedSubWorkflowCaller subWorkflowCaller =
                new FileBasedSubWorkflowCaller(system, this::findWorkflowFile);
            system.addIIActor(new FileBasedSubWorkflowCallerIIAR(
                "subWorkflow", subWorkflowCaller, system));

            // Create and configure interpreter
            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("main-workflow")
                .team(system)
                .build();

            // Register interpreter as actor
            InterpreterIIAR interpreterActor = new InterpreterIIAR("interpreter", interpreter, system);
            system.addIIActor(interpreterActor);

            // Load workflow file
            loadWorkflow(interpreter, mainWorkflowFile);

            LOG.info("Starting workflow execution...");
            LOG.info("-".repeat(50));

            // Execute workflow
            int stepCount = 0;
            int maxSteps = 10000;

            while (stepCount < maxSteps) {
                ActionResult result = interpreter.execCode();
                stepCount++;

                if (verbose) {
                    LOG.info("Step " + stepCount + ": " + result.getResult());
                }

                if (!result.isSuccess()) {
                    LOG.severe("Workflow failed at step " + stepCount + ": " + result.getResult());
                    return 1;
                }

                if (result.getResult().contains("end")) {
                    break;
                }
            }

            if (stepCount >= maxSteps) {
                LOG.severe("Workflow exceeded maximum steps (" + maxSteps + ")");
                return 1;
            }

            LOG.info("-".repeat(50));
            LOG.info("Workflow completed successfully (" + stepCount + " steps)");
            return 0;

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Workflow execution failed", e);
            return 1;
        } finally {
            system.terminate();
        }
    }

    /**
     * Scans the directory recursively for workflow files.
     *
     * @param dir the directory to scan
     */
    private void scanWorkflowDirectory(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isWorkflowFile)
                 .forEach(this::registerWorkflowFile);
        } catch (IOException e) {
            LOG.warning("Error scanning directory: " + e.getMessage());
        }
    }

    /**
     * Checks if a file is a workflow file (YAML, JSON, or XML).
     *
     * @param path the file path
     * @return true if it's a workflow file
     */
    private boolean isWorkflowFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") ||
               name.endsWith(".json") || name.endsWith(".xml");
    }

    /**
     * Registers a workflow file in the cache.
     *
     * @param path the file path
     */
    private void registerWorkflowFile(Path path) {
        File file = path.toFile();
        String fileName = file.getName();

        // Register with full filename
        workflowCache.put(fileName, file);

        // Register without extension
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String baseName = fileName.substring(0, dotIndex);
            // Only register base name if not already taken by another file
            workflowCache.putIfAbsent(baseName, file);
        }
    }

    /**
     * Finds a workflow file by name.
     *
     * @param name the workflow name (with or without extension)
     * @return the workflow file, or null if not found
     */
    public File findWorkflowFile(String name) {
        // Try exact match first
        File file = workflowCache.get(name);
        if (file != null) {
            return file;
        }

        // Try with common extensions
        String[] extensions = {".yaml", ".yml", ".json", ".xml"};
        for (String ext : extensions) {
            file = workflowCache.get(name + ext);
            if (file != null) {
                return file;
            }
        }

        return null;
    }

    /**
     * Loads a workflow file into the interpreter.
     *
     * @param interpreter the interpreter
     * @param file the workflow file
     * @throws Exception if loading fails
     */
    private void loadWorkflow(Interpreter interpreter, File file) throws Exception {
        String fileName = file.getName().toLowerCase();

        try (FileInputStream fis = new FileInputStream(file)) {
            if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                interpreter.readYaml(fis);
            } else if (fileName.endsWith(".json")) {
                interpreter.readJson(fis);
            } else if (fileName.endsWith(".xml")) {
                interpreter.readXml(fis);
            } else {
                throw new IllegalArgumentException(
                    "Unsupported file format: " + fileName);
            }
        }

        LOG.info("Loaded workflow: " + interpreter.getCode().getName());
    }
}
