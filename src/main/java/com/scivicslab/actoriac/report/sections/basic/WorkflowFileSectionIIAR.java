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

package com.scivicslab.actoriac.report.sections.basic;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * IIAR wrapper for {@link WorkflowFileSection}.
 *
 * <p>Exposes the POJO's methods as actions via {@code @Action} annotations.</p>
 *
 * <h2>Usage in workflow YAML:</h2>
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ["reportBuilder", "wfFile", "...WorkflowFileSectionIIAR"]
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowFileSectionIIAR extends IIActorRef<WorkflowFileSection> {

    /**
     * Constructs the IIAR with a new POJO instance.
     *
     * @param actorName the actor name
     * @param system the actor system
     */
    public WorkflowFileSectionIIAR(String actorName, IIActorSystem system) {
        super(actorName, new WorkflowFileSection(), system);
        initializeFromWorkflow();
    }

    /**
     * Initializes the POJO with workflow information from nodeGroup.
     */
    private void initializeFromWorkflow() {
        String workflowPath = getWorkflowPath();
        if (workflowPath != null) {
            object.setWorkflowPath(workflowPath);
        }
    }

    private String getWorkflowPath() {
        if (actorSystem == null || !(actorSystem instanceof IIActorSystem)) return null;
        IIActorSystem iiSystem = (IIActorSystem) actorSystem;

        IIActorRef<?> nodeGroup = iiSystem.getIIActor("nodeGroup");
        if (nodeGroup == null) return null;

        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    // ========================================================================
    // Actions - expose POJO methods
    // ========================================================================

    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }

    @Action("getOrder")
    public ActionResult getOrder(String args) {
        return new ActionResult(true, String.valueOf(object.getOrder()));
    }
}
