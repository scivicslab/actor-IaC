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
import java.util.function.Function;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import com.scivicslab.pojoactor.workflow.Interpreter;

/**
 * File-based sub-workflow caller for CLI usage.
 *
 * <p>This class extends the sub-workflow calling pattern to work with file system
 * based workflows. Instead of loading from classpath resources, it uses a provided
 * file lookup function to find workflow files by name.</p>
 *
 * <h2>Usage:</h2>
 * <p>This caller is designed to work with {@link WorkflowCLI} which provides
 * a file lookup function based on recursively scanned workflow directories.</p>
 *
 * <pre>{@code
 * // Create with file lookup function
 * FileBasedSubWorkflowCaller caller = new FileBasedSubWorkflowCaller(
 *     system, workflowCli::findWorkflowFile);
 *
 * // Register as IIActor for workflow access
 * system.addIIActor(new FileBasedSubWorkflowCallerIIAR("subWorkflow", caller, system));
 *
 * // In workflow YAML:
 * matrix:
 *   - states: ["0", "1"]
 *     actions:
 *       - [subWorkflow, call, "deploy-step.yaml"]
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.7.0
 * @see WorkflowCLI
 * @see Interpreter
 */
public class FileBasedSubWorkflowCaller implements CallableByActionName {

    private static final Logger LOG = Logger.getLogger(FileBasedSubWorkflowCaller.class.getName());

    private final IIActorSystem system;
    private final Function<String, File> fileLookup;
    private int callCount = 0;

    /**
     * Constructs a new FileBasedSubWorkflowCaller.
     *
     * @param system the actor system to use for sub-workflow execution
     * @param fileLookup function that takes a workflow name and returns the File,
     *                   or null if not found
     */
    public FileBasedSubWorkflowCaller(IIActorSystem system, Function<String, File> fileLookup) {
        if (system == null) {
            throw new IllegalArgumentException("ActorSystem cannot be null");
        }
        if (fileLookup == null) {
            throw new IllegalArgumentException("File lookup function cannot be null");
        }
        this.system = system;
        this.fileLookup = fileLookup;
    }

    /**
     * Executes actions by name.
     *
     * <p>Supported actions:</p>
     * <ul>
     *   <li>{@code call} - Calls a sub-workflow by name</li>
     * </ul>
     *
     * @param actionName the name of the action to execute
     * @param args the workflow name (with or without extension)
     * @return ActionResult indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        if ("call".equals(actionName)) {
            return callSubWorkflow(args);
        }
        return new ActionResult(false, "Unknown action: " + actionName);
    }

    /**
     * Calls a sub-workflow synchronously.
     *
     * <p>This method finds the workflow file using the file lookup function,
     * creates a new Interpreter, loads the workflow, and executes all steps.</p>
     *
     * @param workflowName the name of the workflow file (with or without extension)
     * @return ActionResult indicating success or failure
     */
    private ActionResult callSubWorkflow(String workflowName) {
        if (workflowName == null || workflowName.trim().isEmpty()) {
            return new ActionResult(false, "Workflow name cannot be null or empty");
        }

        File workflowFile = fileLookup.apply(workflowName);
        if (workflowFile == null) {
            return new ActionResult(false, "Sub-workflow not found: " + workflowName);
        }

        LOG.info("Calling sub-workflow: " + workflowFile.getName());

        try {
            Interpreter subInterpreter = new Interpreter.Builder()
                .loggerName("sub-workflow-" + callCount)
                .team(system)
                .build();

            loadWorkflow(subInterpreter, workflowFile);

            int stepCount = 0;
            int maxSteps = 1000;

            while (stepCount < maxSteps) {
                ActionResult result = subInterpreter.execCode();
                stepCount++;

                if (!result.isSuccess()) {
                    return new ActionResult(false,
                        "Sub-workflow failed at step " + stepCount + ": " + result.getResult());
                }

                if (result.getResult().contains("end")) {
                    break;
                }
            }

            if (stepCount >= maxSteps) {
                return new ActionResult(false,
                    "Sub-workflow exceeded maximum steps (" + maxSteps + ")");
            }

            callCount++;
            LOG.info("Sub-workflow completed: " + workflowFile.getName() + " (" + stepCount + " steps)");

            return new ActionResult(true,
                "Sub-workflow completed: " + workflowName + " (steps: " + stepCount + ")");

        } catch (Exception e) {
            LOG.severe("Sub-workflow error: " + e.getMessage());
            return new ActionResult(false, "Sub-workflow error: " + e.getMessage());
        }
    }

    /**
     * Loads a workflow file into the interpreter based on file extension.
     *
     * @param interpreter the interpreter to load into
     * @param file the workflow file (YAML, JSON, or XML)
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
                throw new IllegalArgumentException("Unsupported file format: " + fileName);
            }
        }
    }

    /**
     * Returns the number of successful sub-workflow calls.
     *
     * @return the call count
     */
    public int getCallCount() {
        return callCount;
    }
}
