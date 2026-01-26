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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Multiplexer accumulator that forwards output to multiple downstream accumulators.
 *
 * <p>This accumulator receives all output from Node/NodeGroup actors and forwards
 * it to configured downstream accumulators (console, file, database). This ensures
 * that all output destinations receive identical content.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Node/NodeGroup Actors
 *        │
 *        │ all output (cowsay, stdout, stderr)
 *        ▼
 * MultiplexerAccumulator
 *        │
 *        ├─→ ConsoleAccumulator → System.out
 *        ├─→ FileAccumulator → text file
 *        └─→ DatabaseAccumulator → H2 database
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MultiplexerAccumulator multiplexer = new MultiplexerAccumulator();
 * multiplexer.addTarget(new ConsoleAccumulator());
 * multiplexer.addTarget(new FileAccumulator(logFile));
 * multiplexer.addTarget(new DatabaseAccumulator(logStore, sessionId));
 *
 * // All output goes through the multiplexer
 * multiplexer.add("node-1", "stdout", "command output...");
 * multiplexer.add("workflow", "cowsay", cowsayOutput);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.12.0
 */
public class MultiplexerAccumulator implements Accumulator {

    private final List<Accumulator> targets = new ArrayList<>();
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Constructs an empty MultiplexerAccumulator.
     * Use {@link #addTarget(Accumulator)} to add downstream accumulators.
     */
    public MultiplexerAccumulator() {
    }

    /**
     * Adds a downstream accumulator target.
     *
     * <p>All data added to this multiplexer will be forwarded to this target.</p>
     *
     * @param target the downstream accumulator to add
     */
    public void addTarget(Accumulator target) {
        if (target != null) {
            targets.add(target);
        }
    }

    /**
     * Removes a downstream accumulator target.
     *
     * @param target the downstream accumulator to remove
     * @return true if the target was removed, false if it was not found
     */
    public boolean removeTarget(Accumulator target) {
        return targets.remove(target);
    }

    /**
     * Returns the number of downstream targets.
     *
     * @return the number of targets
     */
    public int getTargetCount() {
        return targets.size();
    }

    @Override
    public void add(String source, String type, String data) {
        for (Accumulator target : targets) {
            try {
                target.add(source, type, data);
            } catch (Exception e) {
                // Log but don't fail - other targets should still receive data
                System.err.println("Warning: Failed to write to accumulator target: " + e.getMessage());
            }
        }
        count.incrementAndGet();
    }

    @Override
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("MultiplexerAccumulator: ").append(count.get()).append(" entries forwarded to ")
          .append(targets.size()).append(" targets\n");
        for (int i = 0; i < targets.size(); i++) {
            sb.append("  Target ").append(i + 1).append(": ").append(targets.get(i).getSummary()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public int getCount() {
        return count.get();
    }

    @Override
    public void clear() {
        count.set(0);
        for (Accumulator target : targets) {
            target.clear();
        }
    }
}
