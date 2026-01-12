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

package com.scivicslab.actoriac;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.ricksbrown.cowsay.Cowsay;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;
import com.scivicslab.pojoactor.workflow.Transition;

/**
 * Level 3 wrapper that adds workflow capabilities to a Node POJO.
 *
 * <p>This class extends {@link Interpreter} to provide workflow execution
 * capabilities while delegating SSH operations to a wrapped {@link Node} instance.</p>
 *
 * <p>This demonstrates the three-level architecture of actor-IaC:</p>
 * <ul>
 * <li><strong>Level 1 (POJO):</strong> {@link Node} - pure POJO with SSH functionality</li>
 * <li><strong>Level 2 (Actor):</strong> ActorRef&lt;Node&gt; - actor wrapper for concurrent execution</li>
 * <li><strong>Level 3 (Workflow):</strong> NodeInterpreter - workflow capabilities + IIActorRef wrapper</li>
 * </ul>
 *
 * <p><strong>Design principle:</strong> Node remains a pure POJO, independent of ActorSystem.
 * NodeInterpreter wraps Node to add workflow capabilities without modifying the Node class.</p>
 *
 * @author devteam@scivics-lab.com
 */
public class NodeInterpreter extends Interpreter {

    private static final Logger logger = Logger.getLogger(NodeInterpreter.class.getName());

    /**
     * The wrapped Node POJO that handles actual SSH operations.
     */
    private final Node node;

    /**
     * The overlay directory path for YAML overlay feature.
     */
    private String overlayDir;

    /**
     * The current transition YAML snippet (first 10 lines) for accumulator reporting.
     */
    private String currentTransitionYaml = "";

    /**
     * Set of changed document names detected by workflow.
     * This replaces the /tmp/changed_docs.txt file-based approach.
     */
    private final Set<String> changedDocuments = new HashSet<>();

    /**
     * Constructs a NodeInterpreter that wraps the specified Node.
     *
     * @param node the {@link Node} instance to wrap
     * @param system the actor system for workflow execution
     */
    public NodeInterpreter(Node node, IIActorSystem system) {
        super();
        this.node = node;
        this.system = system;
        // Initialize parent's logger (used by Interpreter.runWorkflow error handling)
        super.logger = logger;
    }

    /**
     * Executes a command on the remote node via SSH.
     *
     * <p>Delegates to the wrapped {@link Node#executeCommand(String)} method.</p>
     *
     * @param command the command to execute
     * @return the result of the command execution
     * @throws IOException if SSH connection fails
     */
    public Node.CommandResult executeCommand(String command) throws IOException {
        return node.executeCommand(command);
    }

    /**
     * Executes a command with sudo privileges on the remote node.
     *
     * <p>Delegates to the wrapped {@link Node#executeSudoCommand(String)} method.
     * Requires SUDO_PASSWORD environment variable to be set.</p>
     *
     * @param command the command to execute with sudo
     * @return the result of the command execution
     * @throws IOException if SSH connection fails or SUDO_PASSWORD is not set
     */
    public Node.CommandResult executeSudoCommand(String command) throws IOException {
        return node.executeSudoCommand(command);
    }

    /**
     * Gets the hostname of the node.
     *
     * @return the hostname
     */
    public String getHostname() {
        return node.getHostname();
    }

    /**
     * Gets the username for SSH connections.
     *
     * @return the username
     */
    public String getUser() {
        return node.getUser();
    }

    /**
     * Gets the SSH port.
     *
     * @return the SSH port number
     */
    public int getPort() {
        return node.getPort();
    }

    /**
     * Gets the wrapped Node instance.
     *
     * <p>This allows direct access to the underlying POJO when needed.</p>
     *
     * @return the wrapped Node
     */
    public Node getNode() {
        return node;
    }

    /**
     * Sets the overlay directory for YAML overlay feature.
     *
     * @param overlayDir the path to the overlay directory containing overlay-conf.yaml
     */
    public void setOverlayDir(String overlayDir) {
        this.overlayDir = overlayDir;
    }

    /**
     * Gets the overlay directory path.
     *
     * @return the overlay directory path, or null if not set
     */
    public String getOverlayDir() {
        return overlayDir;
    }

    /**
     * Hook called when entering a step during workflow execution.
     *
     * <p>Displays the workflow name and first 10 lines of the step definition
     * in YAML format using cowsay to provide visual separation between workflow steps.</p>
     *
     * @param transition the transition being entered
     */
    @Override
    protected void onEnterTransition(Transition transition) {
        // Get workflow name
        String workflowName = (getCode() != null && getCode().getName() != null)
                ? getCode().getName()
                : "unknown-workflow";

        // Get YAML-formatted output (first 10 lines)
        String yamlText = transition.toYamlString(10).trim();
        this.currentTransitionYaml = yamlText;

        // Combine workflow name and step YAML
        String displayText = "[" + workflowName + "]\n" + yamlText;
        String[] cowsayArgs = { displayText };
        System.out.println(Cowsay.say(cowsayArgs));
    }

    /**
     * Returns the current transition YAML snippet for accumulator reporting.
     *
     * @return the first 10 lines of the current transition in YAML format
     */
    public String getCurrentTransitionYaml() {
        return currentTransitionYaml;
    }

    /**
     * Loads and runs a workflow file to completion with overlay support.
     *
     * <p>If overlayDir is set, the workflow is loaded with overlay applied.
     * Variables defined in overlay-conf.yaml are substituted before execution.</p>
     *
     * @param workflowFile the workflow file path (YAML or JSON)
     * @param maxIterations maximum number of state transitions allowed
     * @return ActionResult with success=true if completed, false otherwise
     */
    @Override
    public ActionResult runWorkflow(String workflowFile, int maxIterations) {
        // If no overlay is set, use parent implementation
        if (overlayDir == null) {
            return super.runWorkflow(workflowFile, maxIterations);
        }

        try {
            // Reset state for fresh execution
            reset();

            // Resolve workflow file path
            Path workflowPath;
            if (workflowBaseDir != null) {
                workflowPath = Path.of(workflowBaseDir, workflowFile);
            } else {
                workflowPath = Path.of(workflowFile);
            }

            // Load workflow with overlay applied
            Path overlayPath = Path.of(overlayDir);
            readYaml(workflowPath, overlayPath);

            // Run until end
            return runUntilEnd(maxIterations);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error running workflow with overlay: " + workflowFile, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Document Change Detection API (replaces /tmp file-based approach)
    // ========================================================================

    /**
     * Detects changed documents and stores them in POJO state.
     *
     * <p>This method replaces the shell script that wrote to /tmp/changed_docs.txt.
     * It reads the document list, checks git status for each, and stores changed
     * document names in the changedDocuments set.</p>
     *
     * @param docListPath path to the document list file
     * @return ActionResult with detection summary
     * @throws IOException if file operations fail
     */
    public ActionResult detectDocumentChanges(String docListPath) throws IOException {
        // Clear previous results
        changedDocuments.clear();

        // Expand ~ to home directory
        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        if (!Files.exists(listPath)) {
            return new ActionResult(false, "Document list not found: " + docListPath);
        }

        // Check for FORCE_FULL_BUILD environment variable
        boolean forceBuild = "true".equalsIgnoreCase(System.getenv("FORCE_FULL_BUILD"));

        StringBuilder summary = new StringBuilder();

        if (forceBuild) {
            summary.append("=== FORCE_FULL_BUILD enabled: processing all documents ===\n");
        } else {
            summary.append("=== Detecting changes via git fetch ===\n");
        }

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Parse: path git_url
                String[] parts = line.split("\\s+", 2);
                String path = parts[0].replace("~", System.getProperty("user.home"));
                String docName = Path.of(path).getFileName().toString();

                if (forceBuild) {
                    changedDocuments.add(docName);
                    summary.append("  [FORCE] ").append(docName).append("\n");
                    continue;
                }

                // Check document status
                Path docPath = Path.of(path);
                Path gitPath = docPath.resolve(".git");

                if (!Files.exists(docPath)) {
                    // New document
                    changedDocuments.add(docName);
                    summary.append("  [NEW] ").append(docName).append("\n");
                } else if (!Files.exists(gitPath)) {
                    // Not a git repository
                    changedDocuments.add(docName);
                    summary.append("  [NO-GIT] ").append(docName).append("\n");
                } else {
                    // Check for remote changes using git
                    String status = checkGitStatus(docPath);
                    if (status.startsWith("[CHANGED]") || status.startsWith("[UNKNOWN]")) {
                        changedDocuments.add(docName);
                    }
                    summary.append("  ").append(status).append(" ").append(docName).append("\n");
                }
            }
        }

        summary.append("\n=== Change detection summary ===\n");
        if (changedDocuments.isEmpty()) {
            summary.append("No changes detected. All documents are up to date.\n");
        } else {
            summary.append("Documents to process: ").append(changedDocuments.size()).append("\n");
            for (String doc : changedDocuments) {
                summary.append(doc).append("\n");
            }
        }

        // Print summary (like the original shell script did)
        System.out.println(summary);

        return new ActionResult(true, "Detected " + changedDocuments.size() + " changed documents");
    }

    /**
     * Checks git status for a document directory.
     *
     * @param docPath path to the document directory
     * @return status string like "[CHANGED]", "[UP-TO-DATE]", or "[UNKNOWN]"
     */
    private String checkGitStatus(Path docPath) {
        try {
            // git fetch
            ProcessBuilder fetchPb = new ProcessBuilder("git", "fetch", "origin");
            fetchPb.directory(docPath.toFile());
            fetchPb.redirectErrorStream(true);
            Process fetchProcess = fetchPb.start();
            fetchProcess.waitFor();

            // Get local HEAD
            String local = runGitCommand(docPath, "git", "rev-parse", "HEAD");

            // Get remote HEAD (try main first, then master)
            String remote = runGitCommand(docPath, "git", "rev-parse", "origin/main");
            if (remote == null || remote.equals("unknown")) {
                remote = runGitCommand(docPath, "git", "rev-parse", "origin/master");
            }

            if (local == null || remote == null || local.equals("unknown") || remote.equals("unknown")) {
                return "[UNKNOWN] (cannot determine state)";
            }

            if (!local.equals(remote)) {
                String localShort = local.length() > 7 ? local.substring(0, 7) : local;
                String remoteShort = remote.length() > 7 ? remote.substring(0, 7) : remote;
                return "[CHANGED] (local: " + localShort + ", remote: " + remoteShort + ")";
            }

            return "[UP-TO-DATE]";

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking git status for " + docPath, e);
            return "[UNKNOWN] (error: " + e.getMessage() + ")";
        }
    }

    /**
     * Runs a git command and returns the output.
     */
    private String runGitCommand(Path workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line != null ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Checks if a specific document is in the changed list.
     *
     * @param docName the document name to check
     * @return true if the document was detected as changed
     */
    public boolean isDocumentChanged(String docName) {
        return changedDocuments.contains(docName);
    }

    /**
     * Gets the number of changed documents.
     *
     * @return the count of changed documents
     */
    public int getChangedDocumentsCount() {
        return changedDocuments.size();
    }

    /**
     * Gets all changed document names.
     *
     * @return unmodifiable set of changed document names
     */
    public Set<String> getChangedDocuments() {
        return Set.copyOf(changedDocuments);
    }

    /**
     * Checks if there are any changed documents to process.
     *
     * @return true if at least one document needs processing
     */
    public boolean hasChangedDocuments() {
        return !changedDocuments.isEmpty();
    }

    /**
     * Clears the changed documents list.
     */
    public void clearChangedDocuments() {
        changedDocuments.clear();
    }

    /**
     * Adds a document to the changed list (for testing or manual override).
     *
     * @param docName the document name to add
     */
    public void addChangedDocument(String docName) {
        changedDocuments.add(docName);
    }

    /**
     * Clones changed documents from git.
     *
     * <p>Only clones documents that are in the changedDocuments set.
     * Removes existing directory and does fresh clone to avoid conflicts.</p>
     *
     * @param docListPath path to the document list file
     * @return ActionResult with clone summary
     * @throws IOException if operations fail
     */
    public ActionResult cloneChangedDocuments(String docListPath) throws IOException {
        if (changedDocuments.isEmpty()) {
            System.out.println("=== No documents to clone (all up to date) ===");
            return new ActionResult(true, "No documents to clone");
        }

        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        // Ensure ~/works exists
        node.executeCommand("mkdir -p ~/works");

        System.out.println("=== Cloning changed documents ===");
        int clonedCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+", 2);
                String path = parts[0];
                String gitUrl = parts.length > 1 ? parts[1] : null;
                String docName = Path.of(path).getFileName().toString();

                if (!changedDocuments.contains(docName)) {
                    System.out.println("  [SKIP] " + docName + " (unchanged)");
                    continue;
                }

                if (gitUrl != null && !gitUrl.isEmpty()) {
                    // Remove old and clone fresh
                    System.out.println("=== Cloning: " + gitUrl + " -> " + path + " ===");
                    node.executeCommand("rm -rf " + path);
                    Node.CommandResult result = node.executeCommand("git clone " + gitUrl + " " + path);
                    if (result.getExitCode() == 0) {
                        clonedCount++;
                    } else {
                        System.err.println("Clone failed: " + result.getStderr());
                    }
                } else {
                    System.out.println("=== No git URL specified for: " + path + " ===");
                }
            }
        }

        return new ActionResult(true, "Cloned " + clonedCount + " documents");
    }

    /**
     * Builds changed Docusaurus documents.
     *
     * <p>Only builds documents that are in the changedDocuments set.</p>
     *
     * @param docListPath path to the document list file
     * @return ActionResult with build summary
     * @throws IOException if operations fail
     */
    public ActionResult buildChangedDocuments(String docListPath) throws IOException {
        if (changedDocuments.isEmpty()) {
            System.out.println("=== No documents to build (all up to date) ===");
            return new ActionResult(true, "No documents to build");
        }

        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        System.out.println("=== Building changed documents ===");
        int builtCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+", 2);
                String path = parts[0];
                String docName = Path.of(path).getFileName().toString();

                if (!changedDocuments.contains(docName)) {
                    System.out.println("  [SKIP] " + docName + " (unchanged)");
                    continue;
                }

                System.out.println("=== Building: " + path + " ===");
                Node.CommandResult result = node.executeCommand(
                    "cd " + path + " && yarn install && yarn build"
                );
                if (result.getExitCode() == 0) {
                    builtCount++;
                } else {
                    System.err.println("Build failed for " + docName + ": " + result.getStderr());
                }
            }
        }

        return new ActionResult(true, "Built " + builtCount + " documents");
    }

    /**
     * Copies changed document builds to public_html.
     *
     * <p>Only copies documents that are in the changedDocuments set.</p>
     *
     * @param docListPath path to the document list file
     * @return ActionResult with copy summary
     * @throws IOException if operations fail
     */
    public ActionResult deployChangedDocuments(String docListPath) throws IOException {
        // Ensure public_html exists
        node.executeCommand("mkdir -p ~/public_html");

        if (changedDocuments.isEmpty()) {
            System.out.println("=== No documents to copy (all up to date) ===");
            node.executeCommand("ls -la ~/public_html/");
            return new ActionResult(true, "No documents to copy");
        }

        String expandedPath = docListPath.replace("~", System.getProperty("user.home"));
        Path listPath = Path.of(expandedPath);

        System.out.println("=== Copying changed documents to public_html ===");
        int copiedCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(listPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+", 2);
                String path = parts[0];
                String docName = Path.of(path).getFileName().toString();

                if (!changedDocuments.contains(docName)) {
                    System.out.println("  [SKIP] " + docName + " (unchanged)");
                    continue;
                }

                String buildPath = path + "/build";
                String destPath = "~/public_html/" + docName;

                System.out.println("=== Copying " + docName + " to public_html ===");
                node.executeCommand("rm -rf " + destPath);
                Node.CommandResult result = node.executeCommand(
                    "cp -r " + buildPath + " " + destPath
                );
                if (result.getExitCode() == 0) {
                    copiedCount++;
                } else {
                    System.err.println("Copy failed for " + docName + ": " + result.getStderr());
                }
            }
        }

        System.out.println("=== public_html contents ===");
        node.executeCommand("ls -la ~/public_html/");

        return new ActionResult(true, "Deployed " + copiedCount + " documents");
    }
}
