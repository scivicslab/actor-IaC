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

package com.scivicslab.actoriac.cli;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

import com.scivicslab.actoriac.log.H2LogReader;
import com.scivicslab.actoriac.log.H2LogReader.NodeInfo;
import com.scivicslab.actoriac.log.LogEntry;
import com.scivicslab.actoriac.log.LogLevel;
import com.scivicslab.actoriac.log.SessionSummary;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI tool for querying workflow execution logs from H2 database.
 *
 * <p>Usage examples:</p>
 * <pre>
 * # Show summary of the latest session
 * java -jar actor-IaC.jar logs --db logs --summary
 *
 * # Show logs for a specific node
 * java -jar actor-IaC.jar logs --db logs --node node-001
 *
 * # Show only error logs
 * java -jar actor-IaC.jar logs --db logs --level ERROR
 *
 * # List all sessions
 * java -jar actor-IaC.jar logs --db logs --list
 * </pre>
 *
 * @author devteam@scivics-lab.com
 */
@Command(
    name = "logs",
    mixinStandardHelpOptions = true,
    version = "actor-IaC logs 2.10.0",
    description = "Query workflow execution logs from H2 database."
)
public class LogsCLI implements Callable<Integer> {

    /**
     * ISO 8601 format with timezone offset (e.g., 2026-01-05T10:30:00+09:00).
     */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * System timezone for display.
     */
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    @Option(
        names = {"--db"},
        description = "H2 database path (without .mv.db extension)",
        required = true
    )
    private File dbPath;

    @Option(
        names = {"-s", "--session"},
        description = "Session ID to query (default: latest session)"
    )
    private Long sessionId;

    @Option(
        names = {"-n", "--node"},
        description = "Filter logs by node ID"
    )
    private String nodeId;

    @Option(
        names = {"--level"},
        description = "Minimum log level to show (DEBUG, INFO, WARN, ERROR)",
        defaultValue = "DEBUG"
    )
    private LogLevel minLevel;

    @Option(
        names = {"--summary"},
        description = "Show session summary only"
    )
    private boolean summaryOnly;

    @Option(
        names = {"--list"},
        description = "List recent sessions"
    )
    private boolean listSessions;

    @Option(
        names = {"-w", "--workflow"},
        description = "Filter sessions by workflow name"
    )
    private String workflowFilter;

    @Option(
        names = {"-o", "--overlay"},
        description = "Filter sessions by overlay name"
    )
    private String overlayFilter;

    @Option(
        names = {"-i", "--inventory"},
        description = "Filter sessions by inventory name"
    )
    private String inventoryFilter;

    @Option(
        names = {"--after"},
        description = "Filter sessions started after this time (ISO format: YYYY-MM-DDTHH:mm:ss)"
    )
    private String startedAfter;

    @Option(
        names = {"--since"},
        description = "Filter sessions started within the specified duration (e.g., 12h, 1d, 3d, 1w)"
    )
    private String since;

    @Option(
        names = {"--ended-since"},
        description = "Filter sessions ended within the specified duration (e.g., 1h, 12h, 1d)"
    )
    private String endedSince;

    @Option(
        names = {"--limit"},
        description = "Maximum number of entries to show",
        defaultValue = "100"
    )
    private int limit;

    @Option(
        names = {"--list-nodes"},
        description = "List all nodes in the specified session"
    )
    private boolean listNodes;

    /**
     * Main entry point for the logs CLI.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogsCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        Path path = dbPath.toPath();

        try (H2LogReader reader = new H2LogReader(path)) {
            if (listSessions) {
                return listRecentSessions(reader);
            }

            // Determine session ID
            long targetSession = sessionId != null ? sessionId : reader.getLatestSessionId();
            if (targetSession < 0) {
                System.err.println("No sessions found in database.");
                return 1;
            }

            if (listNodes) {
                return listNodesInSession(reader, targetSession);
            }

            if (summaryOnly) {
                return showSummary(reader, targetSession);
            }

            return showLogs(reader, targetSession);

        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private Integer listRecentSessions(H2LogReader reader) {
        // Parse start time filter (--since takes precedence over --after)
        LocalDateTime startedAfterTime = null;
        if (since != null) {
            startedAfterTime = parseSince(since);
            if (startedAfterTime == null) {
                System.err.println("Invalid --since format. Use: 12h, 1d, 3d, 1w (h=hours, d=days, w=weeks)");
                return 1;
            }
        } else if (startedAfter != null) {
            try {
                startedAfterTime = LocalDateTime.parse(startedAfter);
            } catch (Exception e) {
                System.err.println("Invalid date format. Use ISO format: YYYY-MM-DDTHH:mm:ss");
                return 1;
            }
        }

        // Parse end time filter
        LocalDateTime endedAfterTime = null;
        if (endedSince != null) {
            endedAfterTime = parseSince(endedSince);
            if (endedAfterTime == null) {
                System.err.println("Invalid --ended-since format. Use: 1h, 12h, 1d, 3d (h=hours, d=days, w=weeks)");
                return 1;
            }
        }

        // Apply filters if any are specified
        List<SessionSummary> sessions;
        if (workflowFilter != null || overlayFilter != null || inventoryFilter != null || startedAfterTime != null || endedAfterTime != null) {
            sessions = reader.listSessionsFiltered(workflowFilter, overlayFilter, inventoryFilter, startedAfterTime, endedAfterTime, limit);
        } else {
            sessions = reader.listSessions(limit);
        }

        if (sessions.isEmpty()) {
            System.out.println("No sessions found.");
            return 0;
        }

        System.out.println("Sessions:");
        System.out.println("=".repeat(80));
        for (SessionSummary summary : sessions) {
            System.out.printf("#%-4d %-30s %-10s%n",
                    summary.getSessionId(),
                    summary.getWorkflowName(),
                    summary.getStatus());
            if (summary.getOverlayName() != null) {
                System.out.printf("      Overlay:   %s%n", summary.getOverlayName());
            }
            if (summary.getInventoryName() != null) {
                System.out.printf("      Inventory: %s%n", summary.getInventoryName());
            }
            System.out.printf("      Started:   %s%n", formatTimestamp(summary.getStartedAt()));
            System.out.println("-".repeat(80));
        }
        return 0;
    }

    private Integer listNodesInSession(H2LogReader reader, long targetSession) {
        List<NodeInfo> nodes = reader.getNodesInSession(targetSession);
        if (nodes.isEmpty()) {
            System.out.println("No nodes found in session #" + targetSession);
            return 0;
        }

        SessionSummary summary = reader.getSummary(targetSession);
        System.out.println("Nodes in session #" + targetSession + " (" + summary.getWorkflowName() + "):");
        System.out.println("=".repeat(70));
        System.out.printf("%-30s %-10s %-10s%n", "NODE_ID", "STATUS", "LOG_COUNT");
        System.out.println("-".repeat(70));
        for (NodeInfo node : nodes) {
            System.out.printf("%-30s %-10s %-10d%n",
                    node.nodeId(),
                    node.status() != null ? node.status() : "-",
                    node.logCount());
        }
        System.out.println("=".repeat(70));
        System.out.println("Total: " + nodes.size() + " nodes");
        return 0;
    }

    private Integer showSummary(H2LogReader reader, long targetSession) {
        SessionSummary summary = reader.getSummary(targetSession);
        if (summary == null) {
            System.err.println("Session not found: " + targetSession);
            return 1;
        }
        System.out.println(summary);
        return 0;
    }

    private Integer showLogs(H2LogReader reader, long targetSession) {
        List<LogEntry> logs;

        if (nodeId != null) {
            logs = reader.getLogsByNode(targetSession, nodeId);
            System.out.println("Logs for node: " + nodeId);
        } else {
            logs = reader.getLogsByLevel(targetSession, minLevel);
            System.out.println("Logs (level >= " + minLevel + "):");
        }

        System.out.println("=".repeat(80));

        int count = 0;
        for (LogEntry entry : logs) {
            if (count >= limit) {
                System.out.println("... (truncated, use --limit to show more)");
                break;
            }

            // Format: [timestamp] LEVEL [node] message
            String levelColor = getLevelPrefix(entry.getLevel());
            System.out.printf("%s[%s] %-5s [%s] %s%s%n",
                    levelColor,
                    formatTimestamp(entry.getTimestamp()),
                    entry.getLevel(),
                    entry.getNodeId(),
                    entry.getMessage(),
                    "\u001B[0m"); // Reset color

            count++;
        }

        System.out.println("=".repeat(80));
        System.out.println("Total: " + logs.size() + " entries");

        return 0;
    }

    private String getLevelPrefix(LogLevel level) {
        return switch (level) {
            case ERROR -> "\u001B[31m"; // Red
            case WARN -> "\u001B[33m";  // Yellow
            case INFO -> "\u001B[32m";  // Green
            case DEBUG -> "\u001B[36m"; // Cyan
        };
    }

    /**
     * Formats a LocalDateTime as ISO 8601 string with timezone offset.
     *
     * <p>Example output: 2026-01-05T10:30:00+09:00</p>
     *
     * @param timestamp the timestamp to format
     * @return ISO 8601 formatted string, or "N/A" if null
     */
    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "N/A";
        }
        return timestamp.atZone(SYSTEM_ZONE).format(ISO_FORMATTER);
    }

    /**
     * Parses a relative time string into a LocalDateTime.
     *
     * <p>Supported formats:</p>
     * <ul>
     *   <li>{@code 12h} - 12 hours ago</li>
     *   <li>{@code 1d} - 1 day ago</li>
     *   <li>{@code 3d} - 3 days ago</li>
     *   <li>{@code 1w} - 1 week ago</li>
     * </ul>
     *
     * @param sinceStr the relative time string
     * @return LocalDateTime representing the calculated time, or null if invalid format
     */
    private LocalDateTime parseSince(String sinceStr) {
        if (sinceStr == null || sinceStr.isEmpty()) {
            return null;
        }

        try {
            String numPart = sinceStr.substring(0, sinceStr.length() - 1);
            char unit = Character.toLowerCase(sinceStr.charAt(sinceStr.length() - 1));
            long amount = Long.parseLong(numPart);

            LocalDateTime now = LocalDateTime.now();
            return switch (unit) {
                case 'h' -> now.minusHours(amount);
                case 'd' -> now.minusDays(amount);
                case 'w' -> now.minusWeeks(amount);
                case 'm' -> now.minusMinutes(amount);
                default -> null;
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }
}
