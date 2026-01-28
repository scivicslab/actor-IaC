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

package com.scivicslab.actoriac.accumulator;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Actor reference for MultiplexerAccumulator.
 *
 * <p>This class wraps a {@link MultiplexerAccumulator} as an actor, allowing
 * other actors to send messages to it via {@code callByActionName}. The actor
 * forwards all output to its registered target accumulators (Console, File, Database).</p>
 *
 * <p>This class replaces the former {@code LoggingAccumulatorIIAR} with a more
 * flexible architecture that supports multiple output destinations.</p>
 *
 * <h2>Supported Actions</h2>
 * <ul>
 *   <li>{@code add} - Adds output to all registered accumulators</li>
 *   <li>{@code getSummary} - Returns formatted summary</li>
 *   <li>{@code getCount} - Returns the number of added entries</li>
 *   <li>{@code clear} - Clears all accumulated entries</li>
 * </ul>
 *
 * <h2>Message Format for "add" Action</h2>
 * <pre>{@code
 * {
 *   "source": "node-localhost",
 *   "type": "stdout",
 *   "data": "command output here"
 * }
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create and register the actor
 * MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
 * multiplexer.addTarget(new ConsoleAccumulator());
 * multiplexer.addTarget(new FileAccumulator(logFilePath));
 * multiplexer.addTarget(new DatabaseAccumulator(logStoreActor, dbExecutor, sessionId));
 *
 * MultiplexerAccumulatorIIAR actor = new MultiplexerAccumulatorIIAR(
 *     "outputMultiplexer", multiplexer, system);
 * system.addIIActor(actor);
 *
 * // Other actors can send messages by name
 * IIActorRef<?> multiplexerActor = system.getIIActor("outputMultiplexer");
 * multiplexerActor.callByActionName("add", jsonArg);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
public class MultiplexerAccumulatorIIAR extends IIActorRef<MultiplexerAccumulator> {

    private final Logger logger;

    /**
     * Constructs a new MultiplexerAccumulatorIIAR.
     *
     * @param actorName the name of this actor (e.g., "outputMultiplexer")
     * @param object the MultiplexerAccumulator instance
     * @param system the actor system
     */
    public MultiplexerAccumulatorIIAR(String actorName, MultiplexerAccumulator object, IIActorSystem system) {
        super(actorName, object, system);
        this.logger = Logger.getLogger(actorName);
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Adds output to all registered accumulators.
     *
     * <p>Parses the JSON argument and forwards to the multiplexer, which then
     * distributes to all registered target accumulators.</p>
     *
     * @param arg JSON object with source, type, and data fields
     * @return ActionResult indicating success or failure
     */
    @Action("add")
    public ActionResult add(String arg) {
        // Note: Do NOT log here - it causes infinite loop via MultiplexerLogHandler
        try {
            JSONObject json = new JSONObject(arg);
            String source = json.getString("source");
            String type = json.getString("type");
            String data = json.getString("data");

            // Forward to multiplexer (which distributes to all targets)
            this.tell(acc -> acc.add(source, type, data)).get();

            return new ActionResult(true, "Added");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in add", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Returns formatted summary from all accumulators.
     *
     * @param arg not used
     * @return ActionResult with the formatted summary
     */
    @Action("getSummary")
    public ActionResult getSummary(String arg) {
        try {
            String summary = this.ask(MultiplexerAccumulator::getSummary).get();
            return new ActionResult(true, summary);
        } catch (ExecutionException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error in getSummary", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Returns the number of added entries.
     *
     * @param arg not used
     * @return ActionResult with the count
     */
    @Action("getCount")
    public ActionResult getCount(String arg) {
        try {
            int count = this.ask(MultiplexerAccumulator::getCount).get();
            return new ActionResult(true, String.valueOf(count));
        } catch (ExecutionException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error in getCount", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * Clears all accumulated entries.
     *
     * @param arg not used
     * @return ActionResult indicating success
     */
    @Action("clear")
    public ActionResult clear(String arg) {
        try {
            this.tell(MultiplexerAccumulator::clear).get();
            return new ActionResult(true, "Cleared");
        } catch (ExecutionException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error in clear", e);
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }
}
