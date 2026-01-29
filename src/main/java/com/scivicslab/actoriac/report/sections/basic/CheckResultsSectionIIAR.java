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

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import java.sql.Connection;
import java.util.logging.Logger;

/**
 * IIAR wrapper for {@link CheckResultsSection}.
 *
 * <p>Exposes the POJO's methods as actions via {@code @Action} annotations.
 * Handles database connection and session ID retrieval from the system.</p>
 *
 * <h2>Usage in workflow YAML:</h2>
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ["reportBuilder", "checkResults", "...CheckResultsSectionIIAR"]
 * }</pre>
 *
 * <p>The % prefix notation is used in workflows to mark lines for collection:</p>
 * <pre>
 * echo "%[OK] Service is running"
 * echo "%[ERROR] Config file not found"
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class CheckResultsSectionIIAR extends IIActorRef<CheckResultsSection> {

    private static final Logger logger = Logger.getLogger(CheckResultsSectionIIAR.class.getName());

    /**
     * Constructs the IIAR with a new POJO instance.
     *
     * @param actorName the actor name
     * @param system the actor system
     */
    public CheckResultsSectionIIAR(String actorName, IIActorSystem system) {
        super(actorName, new CheckResultsSection(), system);
        initializeFromSystem();
    }

    /**
     * Initializes the POJO with database connection and session ID.
     */
    private void initializeFromSystem() {
        // Get database connection from DistributedLogStore
        DistributedLogStore logStore = DistributedLogStore.getInstance();
        if (logStore != null) {
            Connection conn = logStore.getConnection();
            if (conn != null) {
                object.setConnection(conn);
                logger.fine("CheckResultsSectionIIAR: initialized database connection");
            }
        }

        // Get session ID from nodeGroup
        long sessionId = getSessionIdFromNodeGroup();
        if (sessionId >= 0) {
            object.setSessionId(sessionId);
            logger.fine("CheckResultsSectionIIAR: initialized sessionId=" + sessionId);
        }
    }

    /**
     * Retrieves session ID from nodeGroup actor.
     */
    private long getSessionIdFromNodeGroup() {
        if (actorSystem == null || !(actorSystem instanceof IIActorSystem)) {
            return -1;
        }
        IIActorSystem iiSystem = (IIActorSystem) actorSystem;

        IIActorRef<?> nodeGroup = iiSystem.getIIActor("nodeGroup");
        if (nodeGroup == null) {
            return -1;
        }

        ActionResult result = nodeGroup.callByActionName("getSessionId", "");
        if (result.isSuccess()) {
            try {
                return Long.parseLong(result.getResult());
            } catch (NumberFormatException e) {
                logger.warning("CheckResultsSectionIIAR: invalid sessionId: " + result.getResult());
            }
        }
        return -1;
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
}
