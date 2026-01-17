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

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Accumulator that outputs to the console (System.out/System.err).
 *
 * <p>This accumulator writes output directly to the console as it arrives.
 * It supports different output types:</p>
 * <ul>
 *   <li>{@code cowsay} - Rendered cowsay ASCII art (output as-is)</li>
 *   <li>{@code stdout} - Command stdout (output as-is)</li>
 *   <li>{@code stderr} - Command stderr (output to System.err)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ConsoleAccumulator console = new ConsoleAccumulator();
 * console.add("node-1", "stdout", "command output line");
 * console.add("workflow", "cowsay", renderedCowsayArt);
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class ConsoleAccumulator implements Accumulator {

    private final PrintStream stdout;
    private final PrintStream stderr;
    private final AtomicInteger count = new AtomicInteger(0);
    private volatile boolean quiet = false;

    /**
     * Constructs a ConsoleAccumulator with default System.out and System.err.
     */
    public ConsoleAccumulator() {
        this(System.out, System.err);
    }

    /**
     * Constructs a ConsoleAccumulator with custom output streams.
     *
     * @param stdout the stream for stdout output
     * @param stderr the stream for stderr output
     */
    public ConsoleAccumulator(PrintStream stdout, PrintStream stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /**
     * Sets quiet mode. When quiet, no output is written.
     *
     * @param quiet true to suppress output, false to enable output
     */
    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Returns whether quiet mode is enabled.
     *
     * @return true if quiet mode is enabled
     */
    public boolean isQuiet() {
        return quiet;
    }

    @Override
    public void add(String source, String type, String data) {
        if (quiet) {
            count.incrementAndGet();
            return;
        }

        if (data == null || data.isEmpty()) {
            count.incrementAndGet();
            return;
        }

        // Format output with fixed-width source prefix on each line
        String output = formatOutput(source, data);

        switch (type) {
            case "stderr":
                stderr.print(output);
                break;
            case "cowsay":
            case "stdout":
            default:
                stdout.print(output);
                break;
        }
        count.incrementAndGet();
    }

    /**
     * Formats the output with a fixed-width source prefix on each line.
     *
     * <p>Every line of output is prefixed with {@code [source]} where source
     * is left-justified in a fixed-width field. This allows multi-line output
     * (such as cowsay ASCII art) to remain properly aligned while still being
     * identifiable by source.</p>
     *
     * <p>Example output:</p>
     * <pre>
     * [node-web-01    ] command output here
     * [workflow       ]  _______________________
     * [workflow       ] < Starting workflow... >
     * [workflow       ]  -----------------------
     * </pre>
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
        sb.append("\n");

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
        return "ConsoleAccumulator: " + count.get() + " entries written to console";
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
