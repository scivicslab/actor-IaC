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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.json.JSONObject;

import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * A java.util.logging Handler that forwards log messages to MultiplexerAccumulatorIIAR.
 *
 * <p>This handler bridges java.util.logging with the MultiplexerAccumulator system,
 * allowing all log messages to be captured in the same output destinations
 * (console, file, database) as command output and cowsay.</p>
 *
 * <h2>Log Format</h2>
 * <p>Log messages are formatted as:</p>
 * <pre>{@code
 * 2026-01-17T12:27:54+09:00 INFO Starting workflow: main-collect-sysinfo
 * }</pre>
 *
 * <h2>Message Format</h2>
 * <p>Messages sent to MultiplexerAccumulator:</p>
 * <pre>{@code
 * {
 *   "source": "cli",
 *   "type": "log-INFO",
 *   "data": "2026-01-17T12:27:54+09:00 INFO Starting workflow: main-collect-sysinfo"
 * }
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // After registering MultiplexerAccumulatorIIAR
 * MultiplexerLogHandler logHandler = new MultiplexerLogHandler(system);
 * Logger.getLogger("").addHandler(logHandler);
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class MultiplexerLogHandler extends Handler {

    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final IIActorSystem system;
    private volatile boolean closed = false;

    /**
     * Constructs a MultiplexerLogHandler.
     *
     * @param system the actor system to retrieve outputMultiplexer from
     */
    public MultiplexerLogHandler(IIActorSystem system) {
        this.system = system;
    }

    @Override
    public void publish(LogRecord record) {
        if (closed || record == null) {
            return;
        }

        // Skip if log level is not loggable
        if (!isLoggable(record)) {
            return;
        }

        // Get the multiplexer actor (lazy lookup for loose coupling)
        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) {
            // Multiplexer not yet registered, skip
            return;
        }

        // Format the log message
        String timestamp = LocalDateTime.now().atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
        String level = record.getLevel().getName();
        String message = formatMessage(record);
        String formattedLog = String.format("%s %s %s", timestamp, level, message);

        // Send to multiplexer
        try {
            JSONObject arg = new JSONObject();
            arg.put("source", getSourceName(record));
            arg.put("type", "log-" + level);
            arg.put("data", formattedLog);
            multiplexer.callByActionName("add", arg.toString());
        } catch (Exception e) {
            // Avoid infinite recursion - don't log errors from the log handler
            System.err.println("MultiplexerLogHandler error: " + e.getMessage());
        }
    }

    /**
     * Formats the log message, including any throwable if present.
     */
    private String formatMessage(LogRecord record) {
        String message = record.getMessage();

        // Handle parameterized messages
        Object[] params = record.getParameters();
        if (params != null && params.length > 0) {
            try {
                message = String.format(message, params);
            } catch (Exception e) {
                // If formatting fails, use the raw message
            }
        }

        // Append throwable if present
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringBuilder sb = new StringBuilder(message);
            sb.append("\n").append(thrown.getClass().getName());
            if (thrown.getMessage() != null) {
                sb.append(": ").append(thrown.getMessage());
            }
            for (StackTraceElement element : thrown.getStackTrace()) {
                sb.append("\n\tat ").append(element);
            }
            message = sb.toString();
        }

        return message;
    }

    /**
     * Determines the source name from the log record.
     */
    private String getSourceName(LogRecord record) {
        String loggerName = record.getLoggerName();
        if (loggerName == null || loggerName.isEmpty()) {
            return "system";
        }

        // Use short name for common patterns
        if (loggerName.startsWith("com.scivicslab.actoriac.cli")) {
            return "cli";
        }
        if (loggerName.startsWith("com.scivicslab.actoriac")) {
            return "actor-iac";
        }
        if (loggerName.startsWith("com.scivicslab.pojoactor")) {
            return "pojo-actor";
        }

        // Return the last part of the logger name
        int lastDot = loggerName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < loggerName.length() - 1) {
            return loggerName.substring(lastDot + 1);
        }

        return loggerName;
    }

    @Override
    public void flush() {
        // No buffering, nothing to flush
    }

    @Override
    public void close() throws SecurityException {
        closed = true;
    }
}
