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
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Comparator;

import com.scivicslab.actoriac.NodeGroup;
import com.scivicslab.actoriac.NodeGroupInterpreter;
import com.scivicslab.actoriac.NodeGroupIIAR;
import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.H2LogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.actoriac.log.SessionStatus;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.kustomize.WorkflowKustomizer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI subcommand to execute actor-IaC workflows.
 *
 * <p>This is the main workflow execution command. It supports executing workflows
 * defined in YAML, JSON, or XML format with optional overlay configuration.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * actor-iac run -d /path/to/workflows -w main-workflow.yaml
 * actor-iac run --dir ./workflows --workflow deploy --threads 8
 * actor-iac run -d ./workflows -w deploy -i inventory.ini -g webservers
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.10.0
 */
@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    version = "actor-IaC run 2.11.0",
    description = "Execute actor-IaC workflows defined in YAML, JSON, or XML format."
)
public class RunCLI implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(RunCLI.class.getName());

    @Option(
        names = {"-d", "--dir"},
        required = true,
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
        names = {"--log-serve"},
        description = "H2 log server address (host:port, e.g., localhost:29090). " +
                     "Enables multiple workflows to share a single log database. " +
                     "Falls back to embedded mode if server is unreachable."
    )
    private String logServer;

    @Option(
        names = {"--no-auto-detect"},
        description = "Disable automatic log server detection. " +
                     "Always use embedded mode instead of auto-detecting running servers."
    )
    private boolean noAutoDetect;

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

    @Option(
        names = {"-L", "--list-workflows"},
        description = "List workflows discovered under --dir and exit"
    )
    private boolean listWorkflows;

    @Option(
        names = {"-q", "--quiet"},
        description = "Suppress all console output (stdout/stderr). Logs are still written to database."
    )
    private boolean quiet;

    @Option(
        names = {"--render-to"},
        description = "Render overlay-applied workflows to specified directory (does not execute)"
    )
    private File renderToDir;

    /** Cache of discovered workflow files: name -> File */
    private final Map<String, File> workflowCache = new HashMap<>();

    /** File handler for logging */
    private FileHandler fileHandler;

    /** Distributed log store (H2 database) */
    private DistributedLogStore logStore;

    /** Current session ID for distributed logging */
    private long sessionId = -1;

    /**
     * Executes the workflow.
     *
     * @return exit code (0 for success, non-zero for failure)
     */
    @Override
    public Integer call() {
        // Validate required options
        // --render-to only requires --dir and --overlay, not --workflow
        if (renderToDir != null) {
            if (overlayDir == null) {
                if (!quiet) {
                    System.err.println("--render-to requires '--overlay=<overlayDir>'");
                    CommandLine.usage(this, System.err);
                }
                return 2;
            }
        } else if (workflowName == null && !listWorkflows) {
            if (!quiet) {
                System.err.println("Missing required option: '--workflow=<workflowName>'");
                System.err.println("Use --list-workflows to see available workflows.");
                CommandLine.usage(this, System.err);
            }
            return 2;
        }

        // In quiet mode, suppress all console output
        if (quiet) {
            suppressConsoleOutput();
        }

        // Setup file logging
        if (!noLog) {
            try {
                setupFileLogging();
            } catch (IOException e) {
                if (!quiet) {
                    System.err.println("Warning: Failed to setup file logging: " + e.getMessage());
                }
            }
        }

        // Configure log level based on verbose flag
        configureLogLevel(verbose);

        // Setup H2 log database (enabled by default, use --no-log-db to disable)
        if (!noLogDb) {
            if (logDbPath == null) {
                // Default: actor-iac-logs in workflow directory
                logDbPath = new File(workflowDir, "actor-iac-logs");
            }
            try {
                setupLogDatabase();
            } catch (SQLException e) {
                if (!quiet) {
                    System.err.println("Warning: Failed to setup log database: " + e.getMessage());
                }
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
     * Suppresses all console output (stdout/stderr) for quiet mode.
     */
    private void suppressConsoleOutput() {
        // Remove ConsoleHandler from root logger
        Logger rootLogger = Logger.getLogger("");
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            if (handler instanceof java.util.logging.ConsoleHandler) {
                rootLogger.removeHandler(handler);
            }
        }

        // Redirect System.out and System.err to null
        java.io.PrintStream nullStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        System.setOut(nullStream);
        System.setErr(nullStream);
    }

    /**
     * Sets up H2 database for distributed logging.
     *
     * <p>Connection priority:</p>
     * <ol>
     *   <li>If --log-serve is specified, connect to that server</li>
     *   <li>If --no-auto-detect is NOT specified and DB file exists, auto-detect matching server</li>
     *   <li>Otherwise, use embedded mode</li>
     * </ol>
     */
    private void setupLogDatabase() throws SQLException {
        Path dbPath = logDbPath.toPath();

        // Priority 1: Explicit log server specified
        if (logServer != null && !logServer.isBlank()) {
            logStore = H2LogStore.createWithFallback(logServer, dbPath);
            System.out.println("Log database: " + logServer + " (TCP mode)");
            return;
        }

        // Priority 2: Auto-detect if enabled and DB file exists
        if (!noAutoDetect) {
            java.io.File dbFile = new java.io.File(dbPath.toAbsolutePath() + ".mv.db");
            if (dbFile.exists()) {
                LogServerDiscovery discovery = new LogServerDiscovery();
                LogServerDiscovery.DiscoveryResult result = discovery.discoverServer(dbPath);

                if (result.isFound()) {
                    // Found matching server, connect to it
                    logStore = H2LogStore.createWithFallback(result.getServerAddress(), dbPath);
                    System.out.println("Auto-detected log server at " + result.getServerAddress());
                    System.out.println("Log database: " + result.getServerAddress() + " (TCP mode)");
                    return;
                } else {
                    // DB exists but no matching server found
                    System.out.println("Warning: Log database exists but no matching log server found.");
                    System.out.println("         Consider starting: ./actor_iac.java log-serve --db " + dbPath);
                }
            }
        }

        // Priority 3: Use embedded mode
        logStore = new H2LogStore(dbPath);
        System.out.println("Log database: " + logDbPath.getAbsolutePath() + ".mv.db (embedded mode)");
    }

    /**
     * Sets up file logging with default or specified log file.
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
     * Configures log level based on verbose flag.
     */
    private void configureLogLevel(boolean verbose) {
        Level targetLevel = verbose ? Level.FINE : Level.INFO;

        // Configure root logger level
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(targetLevel);

        // Configure handlers to respect the new level
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(targetLevel);
        }

        // Configure POJO-actor Interpreter logger
        Logger interpreterLogger = Logger.getLogger("com.scivicslab.pojoactor.workflow.Interpreter");
        interpreterLogger.setLevel(targetLevel);

        if (verbose) {
            LOG.info("Verbose mode enabled - log level set to FINE");
        }
    }

    /**
     * Main execution logic.
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

        if (listWorkflows) {
            printWorkflowList();
            return 0;
        }

        // Handle --render-to option
        if (renderToDir != null) {
            return renderOverlayWorkflows();
        }

        if (workflowName == null || workflowName.isBlank()) {
            LOG.severe("Workflow name is required unless --list-workflows is specified.");
            return 1;
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
     * Renders overlay-applied workflows to the specified directory.
     */
    private Integer renderOverlayWorkflows() {
        if (overlayDir == null) {
            LOG.severe("--render-to requires --overlay to be specified");
            return 1;
        }

        try {
            // Create output directory if it doesn't exist
            if (!renderToDir.exists()) {
                if (!renderToDir.mkdirs()) {
                    LOG.severe("Failed to create output directory: " + renderToDir);
                    return 1;
                }
            }

            LOG.info("=== Rendering Overlay-Applied Workflows ===");
            LOG.info("Overlay directory: " + overlayDir.getAbsolutePath());
            LOG.info("Output directory: " + renderToDir.getAbsolutePath());

            WorkflowKustomizer kustomizer = new WorkflowKustomizer();
            Map<String, Map<String, Object>> workflows = kustomizer.build(overlayDir.toPath());

            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();

            for (Map.Entry<String, Map<String, Object>> entry : workflows.entrySet()) {
                String fileName = entry.getKey();
                Map<String, Object> workflowContent = entry.getValue();

                Path outputPath = renderToDir.toPath().resolve(fileName);

                // Add header comment
                StringBuilder sb = new StringBuilder();
                sb.append("# Rendered from overlay: ").append(overlayDir.getName()).append("\n");
                sb.append("# Source: ").append(fileName).append("\n");
                sb.append("# Generated: ").append(LocalDateTime.now()).append("\n");
                sb.append("#\n");
                sb.append(yaml.dump(workflowContent));

                Files.writeString(outputPath, sb.toString());
                LOG.info("  Written: " + outputPath);
            }

            LOG.info("Rendered " + workflows.size() + " workflow(s) to " + renderToDir.getAbsolutePath());
            return 0;

        } catch (IOException e) {
            LOG.severe("Failed to render workflows: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            LOG.severe("Error during rendering: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Executes workflow with node-based execution.
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
            String overlayName = overlayDir != null ? overlayDir.getName() : null;
            String inventoryName = inventoryFile != null ? inventoryFile.getName() : null;
            sessionId = logStore.startSession(workflowName, overlayName, inventoryName, 1);
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

            // Step 1: Create NodeGroupInterpreter
            NodeGroupInterpreter nodeGroupInterpreter = new NodeGroupInterpreter(nodeGroup, system);
            nodeGroupInterpreter.setWorkflowBaseDir(workflowDir.getAbsolutePath());
            if (overlayDir != null) {
                nodeGroupInterpreter.setOverlayDir(overlayDir.getAbsolutePath());
            }
            if (verbose) {
                nodeGroupInterpreter.setVerbose(true);
            }

            // Inject log store into interpreter for node-level logging
            if (logStore != null) {
                nodeGroupInterpreter.setLogStore(logStore, sessionId);
            }

            // Step 2: Create NodeGroupIIAR and register with system
            NodeGroupIIAR nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroupInterpreter, system);
            system.addIIActor(nodeGroupActor);

            // Step 3: Load the main workflow (with overlay if specified)
            ActionResult loadResult = loadMainWorkflow(nodeGroupActor, mainWorkflowFile, overlayDir);
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
     */
    private boolean isWorkflowFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") ||
               name.endsWith(".json") || name.endsWith(".xml");
    }

    /**
     * Registers a workflow file in the cache.
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
     * Loads the main workflow file with optional overlay support.
     */
    private ActionResult loadMainWorkflow(NodeGroupIIAR nodeGroupActor, File workflowFile, File overlayDir) {
        LOG.info("Loading workflow: " + workflowFile.getAbsolutePath());
        logToDb("cli", LogLevel.INFO, "Loading workflow: " + workflowFile.getName());

        String loadArg;
        if (overlayDir != null) {
            // Pass both workflow path and overlay directory
            loadArg = "[\"" + workflowFile.getAbsolutePath() + "\", \"" + overlayDir.getAbsolutePath() + "\"]";
            LOG.fine("Loading with overlay: " + overlayDir.getAbsolutePath());
        } else {
            // Load without overlay
            loadArg = "[\"" + workflowFile.getAbsolutePath() + "\"]";
        }

        return nodeGroupActor.callByActionName("readYaml", loadArg);
    }

    private void printWorkflowList() {
        List<WorkflowDisplay> displays = scanWorkflowsForDisplay(workflowDir);

        if (displays.isEmpty()) {
            System.out.println("No workflow files found under "
                + workflowDir.getAbsolutePath());
            return;
        }

        System.out.println("Available workflows (directory: "
            + workflowDir.getAbsolutePath() + ")");
        System.out.println("-".repeat(90));
        System.out.printf("%-4s %-25s %-35s %s%n", "#", "File (-w)", "Path", "Workflow Name (in logs)");
        System.out.println("-".repeat(90));
        int index = 1;
        for (WorkflowDisplay display : displays) {
            String wfName = display.workflowName() != null ? display.workflowName() : "(no name)";
            System.out.printf("%2d.  %-25s %-35s %s%n",
                index++, display.baseName(), display.relativePath(), wfName);
        }
        System.out.println("-".repeat(90));
        System.out.println("Use -w <File> with the names shown in the 'File (-w)' column.");
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

    /**
     * Extracts the workflow name from a YAML/JSON/XML file.
     */
    private static String extractWorkflowName(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return extractNameFromYaml(file);
        } else if (fileName.endsWith(".json")) {
            return extractNameFromJson(file);
        }
        return null;
    }

    /**
     * Extracts name field from YAML file using simple line parsing.
     */
    private static String extractNameFromYaml(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Look for "name: value" at the top level (not indented)
                if (line.startsWith("name:")) {
                    String value = line.substring(5).trim();
                    // Remove quotes if present
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value.isEmpty() ? null : value;
                }
                // Stop if we hit a steps: or other major section
                if (line.startsWith("steps:") || line.startsWith("vertices:")) {
                    break;
                }
            }
        } catch (IOException e) {
            // Ignore and return null
        }
        return null;
    }

    /**
     * Extracts name field from JSON file.
     */
    private static String extractNameFromJson(File file) {
        try (java.io.FileReader reader = new java.io.FileReader(file)) {
            org.json.JSONTokener tokener = new org.json.JSONTokener(reader);
            org.json.JSONObject json = new org.json.JSONObject(tokener);
            return json.optString("name", null);
        } catch (Exception e) {
            // Ignore and return null
        }
        return null;
    }

    /**
     * Record for displaying workflow information.
     */
    private static record WorkflowDisplay(String baseName, String relativePath, String workflowName) {}
}
