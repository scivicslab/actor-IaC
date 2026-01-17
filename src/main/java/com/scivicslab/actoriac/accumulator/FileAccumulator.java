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

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Accumulator that writes output to a text file.
 *
 * <p>This accumulator writes all output to a text file as it arrives.
 * The output format is identical to what appears on the console,
 * ensuring consistency across all output destinations.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (FileAccumulator fileAcc = new FileAccumulator(Path.of("run.log"))) {
 *     fileAcc.add("node-1", "stdout", "command output");
 *     fileAcc.add("workflow", "cowsay", renderedCowsayArt);
 * }
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class FileAccumulator implements Accumulator, Closeable {

    private final PrintWriter writer;
    private final Path filePath;
    private final AtomicInteger count = new AtomicInteger(0);
    private volatile boolean closed = false;

    /**
     * Constructs a FileAccumulator that writes to the specified file.
     *
     * @param filePath the path to the output file
     * @throws IOException if the file cannot be opened for writing
     */
    public FileAccumulator(Path filePath) throws IOException {
        this.filePath = filePath;
        this.writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath.toFile())));
    }

    /**
     * Constructs a FileAccumulator that writes to the specified file.
     *
     * @param filePath the path to the output file as a string
     * @throws IOException if the file cannot be opened for writing
     */
    public FileAccumulator(String filePath) throws IOException {
        this(Path.of(filePath));
    }

    /**
     * Returns the path to the output file.
     *
     * @return the file path
     */
    public Path getFilePath() {
        return filePath;
    }

    @Override
    public void add(String source, String type, String data) {
        if (closed || data == null || data.isEmpty()) {
            count.incrementAndGet();
            return;
        }

        // Format output with fixed-width source prefix on each line
        String output = formatOutput(source, data);

        synchronized (writer) {
            writer.print(output);
            writer.flush();
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
        return "FileAccumulator: " + count.get() + " entries written to " + filePath;
    }

    @Override
    public int getCount() {
        return count.get();
    }

    @Override
    public void clear() {
        count.set(0);
    }

    /**
     * Closes the file writer.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            synchronized (writer) {
                writer.close();
            }
        }
    }
}
