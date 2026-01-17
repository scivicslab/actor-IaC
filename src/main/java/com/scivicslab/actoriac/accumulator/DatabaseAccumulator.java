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

package com.scivicslab.actoriac.accumulator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Accumulator that writes output to an H2 database via DistributedLogStore.
 *
 * <p>This accumulator writes all output to the H2 database asynchronously.
 * It uses a dedicated executor to avoid blocking workflow execution.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DatabaseAccumulator dbAcc = new DatabaseAccumulator(logStoreActor, dbExecutor, sessionId);
 * dbAcc.add("node-1", "stdout", "command output");
 * dbAcc.add("workflow", "cowsay", renderedCowsayArt);
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class DatabaseAccumulator implements Accumulator {

    private final ActorRef<DistributedLogStore> logStoreActor;
    private final ExecutorService dbExecutor;
    private final long sessionId;
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Constructs a DatabaseAccumulator.
     *
     * @param logStoreActor the actor reference for the distributed log store
     * @param dbExecutor the executor service for async DB writes
     * @param sessionId the session ID for this workflow execution
     */
    public DatabaseAccumulator(ActorRef<DistributedLogStore> logStoreActor,
                               ExecutorService dbExecutor,
                               long sessionId) {
        this.logStoreActor = logStoreActor;
        this.dbExecutor = dbExecutor;
        this.sessionId = sessionId;
    }

    @Override
    public void add(String source, String type, String data) {
        if (logStoreActor == null || sessionId < 0) {
            count.incrementAndGet();
            return;
        }

        if (data == null || data.isEmpty()) {
            count.incrementAndGet();
            return;
        }

        // Format output with fixed-width source prefix on each line
        String formattedData = formatOutput(source, data);

        // Fire-and-forget: don't wait for DB write to complete
        logStoreActor.tell(
            store -> store.logAction(sessionId, source, type, "output", 0, 0L, formattedData),
            dbExecutor
        );
        count.incrementAndGet();
    }

    /**
     * Formats the output with a fixed-width source prefix on each line.
     *
     * @param source the source identifier (e.g., "node-web-01", "cli")
     * @param data the output data (may contain multiple lines)
     * @return the formatted output string with prefix on each line
     */
    private String formatOutput(String source, String data) {
        String prefix = formatPrefix(source);
        StringBuilder sb = new StringBuilder();

        String[] lines = data.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(prefix).append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Creates a prefix from the source name.
     *
     * @param source the source identifier
     * @return formatted prefix like "[node-web-01] "
     */
    private String formatPrefix(String source) {
        String src = (source != null) ? source : "";
        return "[" + src + "] ";
    }

    @Override
    public String getSummary() {
        return "DatabaseAccumulator: " + count.get() + " entries written to database (session " + sessionId + ")";
    }

    @Override
    public int getCount() {
        return count.get();
    }

    @Override
    public void clear() {
        count.set(0);
    }
}
