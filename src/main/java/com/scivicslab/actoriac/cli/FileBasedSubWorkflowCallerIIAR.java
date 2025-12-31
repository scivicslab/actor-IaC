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

import java.util.logging.Logger;

import org.json.JSONArray;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Interpreter-interfaced actor reference for {@link FileBasedSubWorkflowCaller}.
 *
 * <p>This class wraps a {@link FileBasedSubWorkflowCaller} instance to make it
 * accessible from workflow definitions. It handles JSON argument parsing and
 * delegates to the underlying caller.</p>
 *
 * <h2>Workflow Usage:</h2>
 * <pre>{@code
 * name: main-workflow
 * steps:
 *   - states: [0, 1]
 *     actions:
 *       - actor: subWorkflow
 *         method: call
 *         arguments: ["deploy-step"]
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.7.0
 * @see FileBasedSubWorkflowCaller
 */
public class FileBasedSubWorkflowCallerIIAR extends IIActorRef<FileBasedSubWorkflowCaller> {

    private static final Logger LOG = Logger.getLogger(FileBasedSubWorkflowCallerIIAR.class.getName());

    /**
     * Constructs a new FileBasedSubWorkflowCallerIIAR.
     *
     * @param actorName the name of this actor (used in workflow definitions)
     * @param object the FileBasedSubWorkflowCaller instance
     * @param system the actor system
     */
    public FileBasedSubWorkflowCallerIIAR(String actorName, FileBasedSubWorkflowCaller object,
                                           IIActorSystem system) {
        super(actorName, object, system);
    }

    /**
     * Invokes an action by name with the given arguments.
     *
     * <p>Supported actions:</p>
     * <ul>
     *   <li>{@code call} - Calls a sub-workflow. Arguments should be a JSON array
     *       with the workflow name as the first element.</li>
     *   <li>{@code doNothing} - No-op action for workflow control flow.</li>
     * </ul>
     *
     * @param actionName the name of the action
     * @param arg the argument string (JSON array format for "call")
     * @return ActionResult indicating success or failure
     */
    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        LOG.fine(String.format("actionName = %s, args = %s", actionName, arg));

        if (actionName.equals("call")) {
            String workflowName = extractWorkflowName(arg);
            return this.object.callByActionName(actionName, workflowName);
        }
        else if (actionName.equals("doNothing")) {
            return new ActionResult(true, arg);
        }
        else {
            LOG.warning("Unknown action: " + actionName);
            return new ActionResult(false, "Unknown action: " + actionName);
        }
    }

    /**
     * Extracts the workflow name from JSON array arguments.
     *
     * @param arg the JSON array argument string
     * @return the workflow name
     */
    private String extractWorkflowName(String arg) {
        try {
            JSONArray jsonArray = new JSONArray(arg);
            if (jsonArray.length() == 0) {
                throw new IllegalArgumentException("Workflow name argument is required");
            }
            return jsonArray.getString(0);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid argument format. Expected JSON array with workflow name: " + arg, e);
        }
    }
}
