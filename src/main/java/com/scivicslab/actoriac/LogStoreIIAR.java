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

package com.scivicslab.actoriac;

import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.actoriac.log.SessionStatus;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Actor wrapper for DistributedLogStore.
 *
 * <p>This actor centralizes all database writes for logging. It should be created
 * under ROOT and used by all accumulator actors in the system.</p>
 *
 * <p>To ensure logs are written without blocking workflow execution, this actor
 * should be called using a dedicated ManagedThreadPool with 1 thread:</p>
 *
 * <pre>{@code
 * // Setup
 * system.addManagedThreadPool(1);  // index 1 for DB writes
 * ExecutorService dbPool = system.getManagedThreadPool(1);
 *
 * // Usage from accumulator
 * logStoreActor.tell(store -> store.log(...), dbPool);
 * }</pre>
 *
 * <h2>Actor Tree Position</h2>
 * <pre>
 * ROOT
 * ├── logStore              &lt;-- this actor
 * ├── accumulator           (system-level)
 * └── nodeGroup
 *     ├── accumulator       (workflow-level)
 *     └── node-*
 * </pre>
 *
 * <h2>Supported Actions</h2>
 * <ul>
 *   <li>{@code log} - Log a message with level</li>
 *   <li>{@code logAction} - Log an action result</li>
 *   <li>{@code startSession} - Start a new workflow session</li>
 *   <li>{@code endSession} - End a workflow session</li>
 *   <li>{@code markNodeSuccess} - Mark a node as succeeded</li>
 *   <li>{@code markNodeFailed} - Mark a node as failed</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.13.0
 */
public class LogStoreIIAR extends IIActorRef<DistributedLogStore> {

    private static final Logger logger = Logger.getLogger(LogStoreIIAR.class.getName());

    /**
     * The dedicated executor service for DB writes.
     * Using a single-threaded pool ensures writes are serialized.
     */
    private final ExecutorService dbExecutor;

    /**
     * Constructs a new LogStoreIIAR.
     *
     * @param actorName the name of this actor (typically "logStore")
     * @param logStore the DistributedLogStore implementation
     * @param system the actor system
     * @param dbExecutor the dedicated executor service for DB writes (should be single-threaded)
     */
    public LogStoreIIAR(String actorName, DistributedLogStore logStore,
                        IIActorSystem system, ExecutorService dbExecutor) {
        super(actorName, logStore, system);
        this.dbExecutor = dbExecutor;
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Logs a message with level.
     *
     * @param arg JSON with sessionId, nodeId, level, message
     * @return ActionResult
     */
    @Action("log")
    public ActionResult log(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");
            String levelStr = json.getString("level");
            String message = json.getString("message");

            LogLevel level = LogLevel.valueOf(levelStr);

            this.tell(store -> store.log(sessionId, nodeId, level, message), dbExecutor).get();

            return new ActionResult(true, "Logged");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in log", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Logs an action result.
     *
     * @param arg JSON with sessionId, nodeId, label, actionName, exitCode, durationMs, output
     * @return ActionResult
     */
    @Action("logAction")
    public ActionResult logAction(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");
            String label = json.getString("label");
            String action = json.getString("actionName");
            int exitCode = json.getInt("exitCode");
            long durationMs = json.getLong("durationMs");
            String output = json.getString("output");

            this.tell(store -> store.logAction(sessionId, nodeId, label, action, exitCode, durationMs, output),
                      dbExecutor).get();

            return new ActionResult(true, "Action logged");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in logAction", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Starts a new workflow session.
     *
     * @param arg JSON with workflowName, overlayName, inventoryName, nodeCount
     * @return ActionResult with session ID
     */
    @Action("startSession")
    public ActionResult startSession(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            String workflowName = json.getString("workflowName");
            String overlayName = json.optString("overlayName", null);
            String inventoryName = json.optString("inventoryName", null);
            int nodeCount = json.getInt("nodeCount");

            long sessionId = this.ask(store ->
                store.startSession(workflowName, overlayName, inventoryName, nodeCount),
                dbExecutor).get();

            return new ActionResult(true, String.valueOf(sessionId));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in startSession", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Ends a workflow session.
     *
     * @param arg JSON with sessionId, status
     * @return ActionResult
     */
    @Action("endSession")
    public ActionResult endSession(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String statusStr = json.getString("status");

            SessionStatus status = SessionStatus.valueOf(statusStr);

            this.tell(store -> store.endSession(sessionId, status), dbExecutor).get();

            return new ActionResult(true, "Session ended");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in endSession", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Marks a node as succeeded.
     *
     * @param arg JSON with sessionId, nodeId
     * @return ActionResult
     */
    @Action("markNodeSuccess")
    public ActionResult markNodeSuccess(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");

            this.tell(store -> store.markNodeSuccess(sessionId, nodeId), dbExecutor).get();

            return new ActionResult(true, "Node marked as success");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in markNodeSuccess", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Marks a node as failed.
     *
     * @param arg JSON with sessionId, nodeId, reason
     * @return ActionResult
     */
    @Action("markNodeFailed")
    public ActionResult markNodeFailed(String arg) {
        try {
            JSONObject json = new JSONObject(arg);
            long sessionId = json.getLong("sessionId");
            String nodeId = json.getString("nodeId");
            String reason = json.getString("reason");

            this.tell(store -> store.markNodeFailed(sessionId, nodeId, reason), dbExecutor).get();

            return new ActionResult(true, "Node marked as failed");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in markNodeFailed", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Gets the dedicated executor service for DB writes.
     *
     * @return the DB executor service
     */
    public ExecutorService getDbExecutor() {
        return dbExecutor;
    }
}
