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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

import com.scivicslab.actoriac.NodeGroup;
import com.scivicslab.actoriac.NodeGroupInterpreter;
import com.scivicslab.actoriac.NodeGroupIIAR;
import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.H2LogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.actoriac.log.SessionStatus;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

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
    version = "actor-IaC 2.9.0",
    description = "Execute actor-IaC workflows defined in YAML, JSON, or XML format.",
    subcommands = {LogsCLI.class}
)
public class WorkflowCLI implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(WorkflowCLI.class.getName());

    @Option(
        names = {"-d", "--dir"},
        description = "Directory containing workflow files (searched recursively)"
    )
    private File workflowDir;

    @Option(
        names = {"-w", "--workflow"},
        description = "Name of the main workflow to execute (with or without extension)"
    )
    private String workflowName;

    @Option(
        names = {"-i", "--inventory"},
        description = "Path to Ansible inventory file (enables node-based execution)"
    )
    private File inventoryFile;

    @Option(
        names = {"-g", "--group"},
        description = "Name of the host group to target (requires --inventory)",
        defaultValue = "all"
    )
    private String groupName;

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

    @Option(
        names = {"-o", "--overlay"},
        description = "Overlay directory containing overlay-conf.yaml for environment-specific configuration"
    )
    private File overlayDir;

    @Option(
        names = {"-l", "--log"},
        description = "Log file path (default: actor-iac-YYYYMMDDHHmm.log in current directory)"
    )
    private File logFile;

    @Option(
        names = {"--no-log"},
        description = "Disable file logging (output to console only)"
    )
    private boolean noLog;

    @Option(
        names = {"--log-db"},
        description = "H2 database path for distributed logging (default: actor-iac-logs in workflow directory)"
    )
    private File logDbPath;

    @Option(
        names = {"--no-log-db"},
        description = "Disable H2 database logging"
    )
    private boolean noLogDb;

    @Option(
        names = {"-k", "--ask-pass"},
        description = "Prompt for SSH password (uses password authentication instead of ssh-agent)"
    )
    private boolean askPass;

    @Option(
        names = {"--limit"},
        description = "Limit execution to specific hosts (comma-separated, e.g., '192.168.5.15' or '192.168.5.15,192.168.5.16')"
    )
    private String limitHosts;

    /** Cache of discovered workflow files: name -> File */
    private final Map<String, File> workflowCache = new HashMap<>();

    /** File handler for logging */
    private FileHandler fileHandler;

    /** Distributed log store (H2 database) */
    private DistributedLogStore logStore;

    /** Current session ID for distributed logging */
    private long sessionId = -1;

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
     * Executes the workflow.
     *
     * @return exit code (0 for success, non-zero for failure)
     */
    @Override
    public Integer call() {
        // Validate required options when running workflow (not subcommands)
        if (workflowDir == null || workflowName == null) {
            System.err.println("Missing required options: '--dir=<workflowDir>', '--workflow=<workflowName>'");
            CommandLine.usage(this, System.err);
            return 2;
        }

        // Setup file logging
        if (!noLog) {
            try {
                setupFileLogging();
            } catch (IOException e) {
                System.err.println("Warning: Failed to setup file logging: " + e.getMessage());
            }
        }

        // Setup H2 log database (enabled by default, use --no-log-db to disable)
        if (!noLogDb) {
            if (logDbPath == null) {
                // Default: actor-iac-logs in workflow directory
                logDbPath = new File(workflowDir, "actor-iac-logs");
            }
            try {
                setupLogDatabase();
            } catch (SQLException e) {
                System.err.println("Warning: Failed to setup log database: " + e.getMessage());
            }
        }

        try {
            return executeMain();
        } finally {
            // Clean up resources
            if (fileHandler != null) {
                fileHandler.close();
            }
            if (logStore != null) {
                try {
                    logStore.close();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to close log database: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Sets up H2 database for distributed logging.
     *
     * @throws SQLException if database connection fails
     */
    private void setupLogDatabase() throws SQLException {
        Path dbPath = logDbPath.toPath();
        logStore = new H2LogStore(dbPath);
        System.out.println("Log database: " + logDbPath.getAbsolutePath() + ".mv.db");
    }

    /**
     * Sets up file logging with default or specified log file.
     *
     * @throws IOException if log file cannot be created
     */
    private void setupFileLogging() throws IOException {
        String logFilePath;
        if (logFile != null) {
            logFilePath = logFile.getAbsolutePath();
        } else {
            // Generate default log file name: actor-iac-YYYYMMDDHHmm.log
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            logFilePath = "actor-iac-" + timestamp + ".log";
        }

        fileHandler = new FileHandler(logFilePath, false);
        fileHandler.setFormatter(new SimpleFormatter());

        // Add handler to root logger to capture all logs
        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(fileHandler);

        System.out.println("Logging to: " + logFilePath);
    }

    /**
     * Main execution logic.
     *
     * @return exit code (0 for success, non-zero for failure)
     */
    private Integer executeMain() {
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

        // Validate overlay directory if specified
        if (overlayDir != null) {
            if (!overlayDir.exists()) {
                LOG.severe("Overlay directory does not exist: " + overlayDir);
                return 1;
            }
            if (!overlayDir.isDirectory()) {
                LOG.severe("Overlay path is not a directory: " + overlayDir);
                return 1;
            }
        }

        LOG.info("=== actor-IaC Workflow CLI ===");
        LOG.info("Workflow directory: " + workflowDir.getAbsolutePath());
        LOG.info("Main workflow: " + mainWorkflowFile.getName());
        if (overlayDir != null) {
            LOG.info("Overlay: " + overlayDir.getAbsolutePath());
        }
        LOG.info("Worker threads: " + threads);

        // Execute workflow (use local inventory if none specified)
        return executeWorkflow(mainWorkflowFile);
    }

    /**
     * Executes workflow with node-based execution.
     *
     * <p>This method creates a proper workflow execution hierarchy:</p>
     * <ol>
     *   <li>Create IIActorSystem</li>
     *   <li>Create NodeGroupIIAR and register with system (using inventory or localhost)</li>
     *   <li>Create main Interpreter to execute the main workflow</li>
     *   <li>Main workflow calls nodeGroup.createNodeActors and nodeGroup.apply</li>
     *   <li>apply + runWorkflow executes sub-workflows on each node</li>
     * </ol>
     *
     * @param mainWorkflowFile the main workflow file
     * @return exit code
     */
    private Integer executeWorkflow(File mainWorkflowFile) {
        NodeGroup nodeGroup;

        // Prompt for password if --ask-pass is specified
        String sshPassword = null;
        if (askPass) {
            sshPassword = promptForPassword("SSH password: ");
            if (sshPassword == null) {
                LOG.severe("Password input cancelled");
                return 1;
            }
        }

        IIActorSystem system = new IIActorSystem("actor-iac-cli", threads);

        // Start log session if log database is configured
        if (logStore != null) {
            sessionId = logStore.startSession(workflowName, 1); // nodeCount will be updated later
            logStore.log(sessionId, "cli", LogLevel.INFO, "Starting workflow: " + workflowName);
        }

        try {
            if (inventoryFile != null) {
                // Use specified inventory file
                if (!inventoryFile.exists()) {
                    LOG.severe("Inventory file does not exist: " + inventoryFile);
                    logToDb("cli", LogLevel.ERROR, "Inventory file does not exist: " + inventoryFile);
                    return 1;
                }
                LOG.info("Inventory: " + inventoryFile.getAbsolutePath());
                nodeGroup = new NodeGroup.Builder()
                    .withInventory(new FileInputStream(inventoryFile))
                    .build();
            } else {
                // Use empty NodeGroup for local execution
                LOG.info("Inventory: (none - using localhost)");
                nodeGroup = new NodeGroup();
            }

            // Set SSH password if provided
            if (sshPassword != null) {
                nodeGroup.setSshPassword(sshPassword);
                LOG.info("Authentication: password");
            } else {
                LOG.info("Authentication: ssh-agent");
            }

            // Set host limit if provided
            if (limitHosts != null) {
                nodeGroup.setHostLimit(limitHosts);
                LOG.info("Host limit: " + limitHosts);
            }

            // Step 1: Create NodeGroupInterpreter (wraps NodeGroup with Interpreter capabilities)
            NodeGroupInterpreter nodeGroupInterpreter = new NodeGroupInterpreter(nodeGroup, system);
            nodeGroupInterpreter.setWorkflowBaseDir(workflowDir.getAbsolutePath());
            if (overlayDir != null) {
                nodeGroupInterpreter.setOverlayDir(overlayDir.getAbsolutePath());
            }

            // Inject log store into interpreter for node-level logging
            if (logStore != null) {
                nodeGroupInterpreter.setLogStore(logStore, sessionId);
            }

            // Step 2: Create NodeGroupIIAR and register with system
            NodeGroupIIAR nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroupInterpreter, system);
            system.addIIActor(nodeGroupActor);

            // Step 3: Load the main workflow
            LOG.info("Loading workflow: " + mainWorkflowFile.getAbsolutePath());
            logToDb("cli", LogLevel.INFO, "Loading workflow: " + mainWorkflowFile.getName());

            ActionResult loadResult = nodeGroupActor.callByActionName("readYaml",
                "[\"" + mainWorkflowFile.getAbsolutePath() + "\"]");
            if (!loadResult.isSuccess()) {
                LOG.severe("Failed to load workflow: " + loadResult.getResult());
                logToDb("cli", LogLevel.ERROR, "Failed to load workflow: " + loadResult.getResult());
                endSession(SessionStatus.FAILED);
                return 1;
            }

            // Step 4: Execute the workflow
            LOG.info("Starting workflow execution...");
            LOG.info("-".repeat(50));
            logToDb("cli", LogLevel.INFO, "Starting workflow execution");

            long startTime = System.currentTimeMillis();
            ActionResult result = nodeGroupActor.callByActionName("runUntilEnd", "[10000]");
            long duration = System.currentTimeMillis() - startTime;

            LOG.info("-".repeat(50));
            if (result.isSuccess()) {
                LOG.info("Workflow completed successfully: " + result.getResult());
                logToDb("cli", LogLevel.INFO, "Workflow completed successfully in " + duration + "ms");
                endSession(SessionStatus.COMPLETED);
                return 0;
            } else {
                LOG.severe("Workflow failed: " + result.getResult());
                logToDb("cli", LogLevel.ERROR, "Workflow failed: " + result.getResult());
                endSession(SessionStatus.FAILED);
                return 1;
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Workflow execution failed", e);
            logToDb("cli", LogLevel.ERROR, "Exception: " + e.getMessage());
            endSession(SessionStatus.FAILED);
            return 1;
        } finally {
            system.terminate();
        }
    }

    /**
     * Logs a message to the distributed log store if available.
     */
    private void logToDb(String nodeId, LogLevel level, String message) {
        if (logStore != null && sessionId >= 0) {
            logStore.log(sessionId, nodeId, level, message);
        }
    }

    /**
     * Ends the current session with the given status.
     */
    private void endSession(SessionStatus status) {
        if (logStore != null && sessionId >= 0) {
            logStore.endSession(sessionId, status);
        }
    }

    /**
     * Prompts for a password from the console.
     *
     * <p>Uses System.console() for secure input (password is not echoed).
     * Falls back to System.in if console is not available (e.g., in IDE).</p>
     *
     * @param prompt the prompt message to display
     * @return the entered password, or null if input failed
     */
    private String promptForPassword(String prompt) {
        java.io.Console console = System.console();
        if (console != null) {
            // Secure input - password not echoed
            char[] passwordChars = console.readPassword(prompt);
            if (passwordChars != null) {
                return new String(passwordChars);
            }
            return null;
        } else {
            // Fallback for environments without console (e.g., IDE)
            System.out.print(prompt);
            try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
                if (scanner.hasNextLine()) {
                    return scanner.nextLine();
                }
            }
            return null;
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
}
