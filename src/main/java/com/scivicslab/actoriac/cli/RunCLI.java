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
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Comparator;

import com.scivicslab.actoriac.IaCStreamingAccumulator;
import com.scivicslab.actoriac.NodeGroup;
import com.scivicslab.actoriac.NodeGroupInterpreter;
import com.scivicslab.actoriac.NodeGroupIIAR;
import com.scivicslab.actoriac.accumulator.ConsoleAccumulator;
import com.scivicslab.actoriac.accumulator.DatabaseAccumulator;
import com.scivicslab.actoriac.accumulator.FileAccumulator;
import com.scivicslab.actoriac.accumulator.MultiplexerAccumulator;
import com.scivicslab.actoriac.accumulator.MultiplexerAccumulatorIIAR;
import com.scivicslab.actoriac.accumulator.MultiplexerLogHandler;
import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.H2LogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.actoriac.log.SessionStatus;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.workflow.DynamicActorLoaderActor;
import com.scivicslab.pojoactor.workflow.IIActorRef;
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
 * @author devteam@scivicslab.com
 * @since 2.10.0
 */
@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    version = "actor-IaC run 2.12.1",
    description = "Execute actor-IaC workflows defined in YAML, JSON, or XML format."
)
public class RunCLI implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(RunCLI.class.getName());
    private static final String ACTORIAC_VERSION = "2.12.1";

    @Option(
        names = {"-d", "--dir"},
        description = "Base directory (logs are created here). Defaults to current directory.",
        defaultValue = "."
    )
    private File workflowDir;

    @Option(
        names = {"-w", "--workflow"},
        description = "Workflow file path relative to -d (required)",
        required = true
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
        names = {"--file-log", "-l", "--log"},
        description = "Enable text file logging and specify output path. If not specified, only database logging is used."
    )
    private File logFile;

    @Option(
        names = {"--no-file-log", "--no-log"},
        description = "Explicitly disable text file logging. (Text logging is disabled by default.)"
    )
    private boolean noLog;

    @Option(
        names = {"--log-db"},
        description = "H2 database path for distributed logging (default: actor-iac-logs in current directory)"
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
        names = {"--cowfile", "-c"},
        description = "Cowsay character to use for step display. " +
                     "Available: tux, dragon, stegosaurus, kitty, bunny, turtle, elephant, " +
                     "ghostbusters, vader, and 35 more. Use '--cowfile list' to see all."
    )
    private String cowfile;

    @Option(
        names = {"--render-to"},
        description = "Render overlay-applied workflows to specified directory (does not execute)"
    )
    private File renderToDir;

    @Option(
        names = {"--max-steps", "-m"},
        description = "Maximum number of state transitions allowed (default: ${DEFAULT-VALUE}). " +
                     "Use a smaller value for debugging to prevent infinite loops.",
        defaultValue = "10000"
    )
    private int maxSteps;

    /** Cache of discovered workflow files: name -> File */
    private final Map<String, File> workflowCache = new HashMap<>();

    /** Distributed log store (H2 database) */
    private DistributedLogStore logStore;

    /** Actor reference for the log store (for async writes) */
    private ActorRef<DistributedLogStore> logStoreActor;

    /** Dedicated executor service for DB writes */
    private ExecutorService dbExecutor;

    /** Current session ID for distributed logging */
    private long sessionId = -1;

    /**
     * Executes the workflow.
     *
     * @return exit code (0 for success, non-zero for failure)
     */
    @Override
    public Integer call() {
        // Handle --cowfile list: show available cowfiles and exit
        if ("list".equalsIgnoreCase(cowfile)) {
            printAvailableCowfiles();
            return 0;
        }

        // Validate --dir is required for all other operations
        if (workflowDir == null) {
            System.err.println("Missing required option: '--dir=<workflowDir>'");
            CommandLine.usage(this, System.err);
            return 2;
        }

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

        // Configure log level based on verbose flag
        configureLogLevel(verbose);

        // Setup H2 log database (enabled by default, use --no-log-db to disable)
        if (!noLogDb) {
            if (logDbPath == null) {
                // Default: actor-iac-logs in current directory
                logDbPath = new File("actor-iac-logs");
            }
            try {
                setupLogDatabase();
            } catch (SQLException e) {
                if (!quiet) {
                    System.err.println("Warning: Failed to setup log database: " + e.getMessage());
                }
            }
        }

        // Setup text file logging via H2LogStore (only when -l/--log option is specified)
        if (logFile != null && logStore != null) {
            try {
                setupTextLogging();
            } catch (IOException e) {
                if (!quiet) {
                    System.err.println("Warning: Failed to setup text file logging: " + e.getMessage());
                }
            }
        }

        try {
            return executeMain();
        } finally {
            // Clean up resources (H2LogStore.close() also closes text log writer)
            // Clear singleton before closing
            DistributedLogStore.setInstance(null);
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
     * <p>Uses H2's AUTO_SERVER mode which automatically handles multiple processes
     * accessing the same database. The first process starts a TCP server, and
     * subsequent processes connect to it automatically.</p>
     */
    private void setupLogDatabase() throws SQLException {
        Path dbPath = logDbPath.toPath();
        logStore = new H2LogStore(dbPath);
        // Set singleton for WorkflowReporter and other components
        DistributedLogStore.setInstance(logStore);
        System.out.println("Log database: " + logDbPath.getAbsolutePath() + ".mv.db");
    }

    /**
     * Sets up text file logging via H2LogStore.
     *
     * <p>This method configures the H2LogStore to also write log entries
     * to a text file, in addition to the H2 database. Only called when
     * -l/--log option is explicitly specified.</p>
     */
    private void setupTextLogging() throws IOException {
        Path logFilePath = logFile.toPath();

        // Configure H2LogStore to also write to text file
        ((H2LogStore) logStore).setTextLogFile(logFilePath);

        LOG.info("Text logging enabled: " + logFilePath);
    }

    /**
     * Configures log level based on verbose flag.
     */
    private void configureLogLevel(boolean verbose) {
        Level targetLevel = verbose ? Level.FINER : Level.INFO;

        // Try to load logging.properties from classpath if verbose mode
        if (verbose) {
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("logging.properties")) {
                if (is != null) {
                    LogManager.getLogManager().readConfiguration(is);
                    LOG.info("Loaded logging.properties from classpath");
                }
            } catch (java.io.IOException e) {
                LOG.fine("Could not load logging.properties: " + e.getMessage());
            }
        }

        // Configure root logger level
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(targetLevel);

        // Configure handlers to respect the new level
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(targetLevel);
        }

        // Configure POJO-actor loggers for tracing
        Logger interpreterLogger = Logger.getLogger("com.scivicslab.pojoactor.workflow.Interpreter");
        interpreterLogger.setLevel(targetLevel);
        Logger actorSystemLogger = Logger.getLogger("com.scivicslab.pojoactor.workflow.IIActorSystem");
        actorSystemLogger.setLevel(targetLevel);
        Logger genericIIARLogger = Logger.getLogger("com.scivicslab.pojoactor.workflow.DynamicActorLoaderActor$GenericIIAR");
        genericIIARLogger.setLevel(targetLevel);

        if (verbose) {
            LOG.info("Verbose mode enabled - log level set to FINER (tracing enabled)");
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

        // Add dedicated thread pool for DB writes (single thread to serialize writes)
        system.addManagedThreadPool(1);
        dbExecutor = system.getManagedThreadPool(1);

        // Create logStore actor if log database is configured
        if (logStore != null) {
            logStoreActor = system.actorOf("logStore", logStore);

            // Start log session with execution context
            String overlayName = overlayDir != null ? overlayDir.getName() : null;
            String inventoryName = inventoryFile != null ? inventoryFile.getName() : null;

            // Collect execution context for reproducibility
            String cwd = System.getProperty("user.dir");
            String gitCommit = getGitCommit(workflowDir);
            String gitBranch = getGitBranch(workflowDir);
            String commandLine = buildCommandLine();
            String actorIacVersion = ACTORIAC_VERSION;
            String actorIacCommit = getActorIacCommit();

            sessionId = logStore.startSession(workflowName, overlayName, inventoryName, 1,
                    cwd, gitCommit, gitBranch, commandLine, actorIacVersion, actorIacCommit);
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
            // Set workflow base dir to the directory containing the main workflow file
            // so that sub-workflows can be found relative to the main workflow
            File workflowBaseDir = mainWorkflowFile.getParentFile();
            if (workflowBaseDir == null) {
                workflowBaseDir = workflowDir;
            }
            nodeGroupInterpreter.setWorkflowBaseDir(workflowBaseDir.getAbsolutePath());
            if (overlayDir != null) {
                nodeGroupInterpreter.setOverlayDir(overlayDir.getAbsolutePath());
            }
            if (verbose) {
                nodeGroupInterpreter.setVerbose(true);
            }
            // Create IaCStreamingAccumulator for cowsay display
            IaCStreamingAccumulator accumulator = new IaCStreamingAccumulator();
            if (cowfile != null && !cowfile.isBlank()) {
                accumulator.setCowfile(cowfile);
            }
            nodeGroupInterpreter.setAccumulator(accumulator);

            // Inject log store into interpreter for node-level logging
            if (logStore != null) {
                nodeGroupInterpreter.setLogStore(logStore, logStoreActor, dbExecutor, sessionId);
            }

            // Step 2: Create NodeGroupIIAR and register with system
            NodeGroupIIAR nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroupInterpreter, system);
            system.addIIActor(nodeGroupActor);

            // Step 2.5: Create and register MultiplexerAccumulatorIIAR
            MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();

            // Add ConsoleAccumulator (unless --quiet is specified)
            if (!quiet) {
                multiplexer.addTarget(new ConsoleAccumulator());
            }

            // Add FileAccumulator if --file-log is specified
            if (logFile != null) {
                try {
                    multiplexer.addTarget(new FileAccumulator(logFile.toPath()));
                    LOG.info("File logging enabled: " + logFile.getAbsolutePath());
                } catch (IOException e) {
                    LOG.warning("Failed to create file accumulator: " + e.getMessage());
                }
            }

            // Add DatabaseAccumulator if log store is configured
            if (logStoreActor != null && sessionId >= 0) {
                multiplexer.addTarget(new DatabaseAccumulator(logStoreActor, dbExecutor, sessionId));
                LOG.fine("Database logging enabled for session: " + sessionId);
            }

            // Register multiplexer actor with the system
            MultiplexerAccumulatorIIAR multiplexerActor = new MultiplexerAccumulatorIIAR(
                "outputMultiplexer", multiplexer, system);
            system.addIIActor(multiplexerActor);
            LOG.fine("MultiplexerAccumulator registered as 'outputMultiplexer'");

            // Step 2.6: Create and register DynamicActorLoaderActor for plugin loading
            DynamicActorLoaderActor loaderActor = new DynamicActorLoaderActor(system);
            IIActorRef<DynamicActorLoaderActor> loaderRef = new IIActorRef<>("loader", loaderActor, system) {
                @Override
                public ActionResult callByActionName(String actionName, String args) {
                    return loaderActor.callByActionName(actionName, args);
                }
            };
            system.addIIActor(loaderRef);
            LOG.fine("DynamicActorLoaderActor registered as 'loader'");

            // Add log handler to forward java.util.logging to multiplexer
            MultiplexerLogHandler logHandler = new MultiplexerLogHandler(system);
            logHandler.setLevel(java.util.logging.Level.ALL);
            java.util.logging.Logger.getLogger("").addHandler(logHandler);

            // Step 3: Load the main workflow (with overlay if specified)
            ActionResult loadResult = loadMainWorkflow(nodeGroupActor, mainWorkflowFile, overlayDir);
            if (!loadResult.isSuccess()) {
                LOG.severe("Failed to load workflow: " + loadResult.getResult());
                logToDb("cli", LogLevel.ERROR, "Failed to load workflow: " + loadResult.getResult());
                endSession(SessionStatus.FAILED);
                return 1;
            }

            // Step 3.5: Create node actors if group is specified
            if (groupName != null) {
                LOG.info("Creating node actors for group: " + groupName);
                logToDb("cli", LogLevel.INFO, "Creating node actors for group: " + groupName);
                ActionResult createResult = nodeGroupActor.callByActionName("createNodeActors", "[\"" + groupName + "\"]");
                if (!createResult.isSuccess()) {
                    LOG.severe("Failed to create node actors: " + createResult.getResult());
                    logToDb("cli", LogLevel.ERROR, "Failed to create node actors: " + createResult.getResult());
                    endSession(SessionStatus.FAILED);
                    return 1;
                }
                LOG.info("Node actors created: " + createResult.getResult());
            }

            // Step 4: Execute the workflow
            LOG.info("Starting workflow execution...");
            LOG.info("Max steps: " + maxSteps);
            LOG.info("-".repeat(50));
            logToDb("cli", LogLevel.INFO, "Starting workflow execution (max steps: " + maxSteps + ")");

            long startTime = System.currentTimeMillis();
            ActionResult result = nodeGroupActor.callByActionName("runUntilEnd", "[" + maxSteps + "]");
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
     * Scans the immediate directory for workflow files (non-recursive).
     *
     * @since 2.12.1
     */
    private void scanWorkflowDirectory(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
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
     * Finds a workflow file by path relative to workflowDir.
     *
     * <p>Supports paths like "sysinfo/main-collect-sysinfo.yaml" or
     * "sysinfo/main-collect-sysinfo" (extension auto-detected).</p>
     *
     * @param path workflow file path relative to workflowDir
     * @return the workflow file, or null if not found
     * @since 2.12.1
     */
    public File findWorkflowFile(String path) {
        // Resolve path relative to workflowDir
        File file = new File(workflowDir, path);

        // Try exact path first
        if (file.isFile()) {
            return file;
        }

        // Try with common extensions
        String[] extensions = {".yaml", ".yml", ".json", ".xml"};
        for (String ext : extensions) {
            File candidate = new File(workflowDir, path + ext);
            if (candidate.isFile()) {
                return candidate;
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

    /**
     * Prints available cowfiles for cowsay output customization.
     */
    private void printAvailableCowfiles() {
        System.out.println("Available cowfiles for --cowfile option:");
        System.out.println("=".repeat(70));
        System.out.println();

        // Get list from Cowsay library
        String[] listArgs = { "-l" };
        String cowList = com.github.ricksbrown.cowsay.Cowsay.say(listArgs);

        // Split and format nicely
        String[] cowfiles = cowList.trim().split("\\s+");
        System.out.println("Total: " + cowfiles.length + " cowfiles");
        System.out.println();

        // Print in columns
        int cols = 4;
        int colWidth = 17;
        for (int i = 0; i < cowfiles.length; i++) {
            System.out.printf("%-" + colWidth + "s", cowfiles[i]);
            if ((i + 1) % cols == 0) {
                System.out.println();
            }
        }
        if (cowfiles.length % cols != 0) {
            System.out.println();
        }

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("Usage: actor_iac.java run -d <dir> -w <workflow> --cowfile tux");
        System.out.println();
        System.out.println("Popular choices:");
        System.out.println("  tux         - Linux penguin (great for server work)");
        System.out.println("  dragon      - Majestic dragon");
        System.out.println("  stegosaurus - Prehistoric dinosaur");
        System.out.println("  turtle      - Slow and steady");
        System.out.println("  ghostbusters - Who you gonna call?");
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

    /**
     * Scans the immediate directory for workflow files (non-recursive).
     *
     * @since 2.12.1
     */
    private static List<WorkflowDisplay> scanWorkflowsForDisplay(File directory) {
        if (directory == null) {
            return List.of();
        }

        try (Stream<Path> paths = Files.list(directory.toPath())) {
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

    /**
     * Gets the git commit hash of the specified directory.
     *
     * @param dir directory to check (uses current directory if null)
     * @return short git commit hash, or null if not a git repository
     */
    private String getGitCommit(File dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--short", "HEAD");
            if (dir != null) {
                pb.directory(dir);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();
            return exitCode == 0 && !output.isEmpty() ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the git branch name of the specified directory.
     *
     * @param dir directory to check (uses current directory if null)
     * @return git branch name, or null if not a git repository
     */
    private String getGitBranch(File dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            if (dir != null) {
                pb.directory(dir);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();
            return exitCode == 0 && !output.isEmpty() ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds a command line string from the current options.
     *
     * @return command line string
     */
    private String buildCommandLine() {
        StringBuilder sb = new StringBuilder("run");
        if (workflowDir != null) {
            sb.append(" -d ").append(workflowDir.getPath());
        }
        if (workflowName != null) {
            sb.append(" -w ").append(workflowName);
        }
        if (inventoryFile != null) {
            sb.append(" -i ").append(inventoryFile.getPath());
        }
        if (groupName != null && !groupName.equals("all")) {
            sb.append(" -g ").append(groupName);
        }
        if (overlayDir != null) {
            sb.append(" -o ").append(overlayDir.getPath());
        }
        if (threads != 4) {
            sb.append(" -t ").append(threads);
        }
        if (verbose) {
            sb.append(" -v");
        }
        if (quiet) {
            sb.append(" -q");
        }
        if (limitHosts != null) {
            sb.append(" --limit ").append(limitHosts);
        }
        return sb.toString();
    }

    /**
     * Gets the git commit hash of the actor-IaC installation.
     * This looks for a .git directory relative to where actor-IaC is running.
     *
     * @return short git commit hash, or null if not available
     */
    private String getActorIacCommit() {
        // Try to get commit from the actor-IaC source directory if running in dev mode
        try {
            String classPath = RunCLI.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            File classFile = new File(classPath);
            // If running from target/classes, go up to project root
            if (classFile.getPath().contains("target")) {
                File projectRoot = classFile.getParentFile();
                while (projectRoot != null && !new File(projectRoot, ".git").exists()) {
                    projectRoot = projectRoot.getParentFile();
                }
                if (projectRoot != null) {
                    return getGitCommit(projectRoot);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}
