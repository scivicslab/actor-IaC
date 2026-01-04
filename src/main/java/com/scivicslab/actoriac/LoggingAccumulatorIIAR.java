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

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Accumulator actor reference that also logs to H2 database.
 *
 * <p>This class extends the standard accumulator functionality to also write
 * logs to the H2LogStore for persistent storage. Each node's output is stored
 * with the node ID for later querying.</p>
 *
 * <h2>Supported Actions</h2>
 * <ul>
 *   <li>{@code add} - Adds a result and logs to database</li>
 *   <li>{@code getSummary} - Returns formatted summary of all results</li>
 *   <li>{@code getCount} - Returns the number of added results</li>
 *   <li>{@code clear} - Clears all accumulated results</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.9.0
 */
public class LoggingAccumulatorIIAR extends IIActorRef<Accumulator> {

    private final Logger logger;
    private final DistributedLogStore logStore;
    private final long sessionId;

    /**
     * Constructs a new LoggingAccumulatorIIAR.
     *
     * @param actorName the name of this actor
     * @param object the Accumulator implementation
     * @param system the actor system
     * @param logStore the distributed log store for database logging
     * @param sessionId the session ID for this workflow execution
     */
    public LoggingAccumulatorIIAR(String actorName, Accumulator object, IIActorSystem system,
                                   DistributedLogStore logStore, long sessionId) {
        super(actorName, object, system);
        this.logger = Logger.getLogger(actorName);
        this.logStore = logStore;
        this.sessionId = sessionId;
    }

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        logger.fine(String.format("actionName = %s, arg = %s", actionName, arg));

        try {
            switch (actionName) {
                case "add":
                    return handleAdd(arg);

                case "getSummary":
                    return handleGetSummary();

                case "getCount":
                    return handleGetCount();

                case "clear":
                    return handleClear();

                default:
                    logger.warning("Unknown action: " + actionName);
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in " + actionName, e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Handles the add action.
     *
     * <p>Adds the result to the accumulator (for console output) and also
     * logs to the H2 database for persistent storage.</p>
     *
     * @param arg JSON object with source, type, and data fields
     * @return ActionResult indicating success or failure
     */
    private ActionResult handleAdd(String arg) throws ExecutionException, InterruptedException {
        JSONObject json = new JSONObject(arg);
        String source = json.getString("source");
        String type = json.getString("type");
        String data = json.getString("data");

        // Add to accumulator (console output)
        this.tell(acc -> acc.add(source, type, data)).get();

        // Log to H2 database
        if (logStore != null && sessionId >= 0) {
            // source = node ID (e.g., "node-192.168.1.1")
            // type = vertex YAML snippet (what step is being executed)
            // data = command output
            logStore.logAction(sessionId, source, type, "executeCommand", 0, 0L, data);
        }

        return new ActionResult(true, "Added");
    }

    /**
     * Handles the getSummary action.
     *
     * @return ActionResult with the formatted summary
     */
    private ActionResult handleGetSummary() throws ExecutionException, InterruptedException {
        String summary = this.ask(Accumulator::getSummary).get();
        return new ActionResult(true, summary);
    }

    /**
     * Handles the getCount action.
     *
     * @return ActionResult with the count
     */
    private ActionResult handleGetCount() throws ExecutionException, InterruptedException {
        int count = this.ask(Accumulator::getCount).get();
        return new ActionResult(true, String.valueOf(count));
    }

    /**
     * Handles the clear action.
     *
     * @return ActionResult indicating success
     */
    private ActionResult handleClear() throws ExecutionException, InterruptedException {
        this.tell(Accumulator::clear).get();
        return new ActionResult(true, "Cleared");
    }
}
