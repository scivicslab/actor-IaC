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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.h2.tools.Server;

import com.scivicslab.actoriac.log.H2LogStore;

/**
 * Actor that wraps the H2 TCP server and HTTP info server.
 *
 * <p>This POJO actor manages the lifecycle of both servers and tracks
 * connection activity for auto-shutdown functionality.</p>
 *
 * <p><strong>State Tracking:</strong></p>
 * <ul>
 *   <li>Tracks last activity time (session creation, log writes)</li>
 *   <li>Tracks active connection count via TCP port inspection</li>
 *   <li>Provides shutdown method for graceful termination</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 * @since 2.11.0
 */
public class LogServerActor {

    private static final Logger LOG = Logger.getLogger(LogServerActor.class.getName());

    private final int tcpPort;
    private final int httpPort;
    private final File dbPath;
    private final boolean verbose;

    private Server tcpServer;
    private HttpServer httpServer;
    private OffsetDateTime startedAt;
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger lastKnownSessionCount = new AtomicInteger(0);

    private volatile boolean running = false;
    private CountDownLatch shutdownLatch;

    /**
     * Creates a new LogServerActor.
     *
     * @param tcpPort H2 TCP port
     * @param httpPort HTTP info API port
     * @param dbPath database file path (without extension)
     * @param verbose enable verbose logging
     */
    public LogServerActor(int tcpPort, int httpPort, File dbPath, boolean verbose) {
        this.tcpPort = tcpPort;
        this.httpPort = httpPort;
        this.dbPath = dbPath;
        this.verbose = verbose;
    }

    /**
     * Starts both TCP and HTTP servers.
     *
     * @param latch countdown latch to signal when server stops
     * @throws Exception if server startup fails
     */
    public void start(CountDownLatch latch) throws Exception {
        this.shutdownLatch = latch;
        this.startedAt = OffsetDateTime.now();
        this.lastActivityTime.set(System.currentTimeMillis());

        // Start H2 TCP server
        String[] serverArgs = new String[]{
            "-tcp",
            "-tcpPort", String.valueOf(tcpPort),
            "-ifNotExists"
        };
        tcpServer = Server.createTcpServer(serverArgs);
        tcpServer.start();

        // Initialize database schema
        initializeDatabase();

        // Start HTTP info server
        startHttpServer();

        running = true;

        if (verbose) {
            LOG.info("LogServerActor started (TCP: " + tcpPort + ", HTTP: " + httpPort + ")");
        }
    }

    /**
     * Stops both servers gracefully.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        LOG.info("LogServerActor stopping...");

        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (tcpServer != null) {
            tcpServer.stop();
        }

        if (shutdownLatch != null) {
            shutdownLatch.countDown();
        }

        LOG.info("LogServerActor stopped.");
    }

    /**
     * Checks if the server is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gets the number of active TCP connections to this server.
     * Uses netstat/ss to count established connections.
     *
     * @return number of active connections, or -1 if unable to determine
     */
    public int getActiveConnectionCount() {
        try {
            // Try ss first (faster on Linux)
            ProcessBuilder pb = new ProcessBuilder(
                "ss", "-tn", "state", "established",
                "sport", "=", ":" + tcpPort
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            int count = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Skip header line and count established connections
                    if (line.contains("ESTAB") || line.contains(":" + tcpPort)) {
                        count++;
                    }
                }
            }
            process.waitFor();

            // Subtract 1 for header line if present
            return Math.max(0, count - 1);

        } catch (Exception e) {
            // Fallback: try netstat
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "netstat", "-tn"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                int count = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains(":" + tcpPort) && line.contains("ESTABLISHED")) {
                            count++;
                        }
                    }
                }
                process.waitFor();
                return count;

            } catch (Exception e2) {
                if (verbose) {
                    LOG.warning("Unable to determine connection count: " + e2.getMessage());
                }
                return -1;
            }
        }
    }

    /**
     * Gets the time since last activity in milliseconds.
     */
    public long getIdleTimeMillis() {
        return System.currentTimeMillis() - lastActivityTime.get();
    }

    /**
     * Records that activity occurred (called when sessions are created or logs written).
     */
    public void recordActivity() {
        lastActivityTime.set(System.currentTimeMillis());
    }

    /**
     * Checks if there has been any database activity since last check.
     * Compares session count to detect new sessions.
     *
     * @return true if new sessions were created
     */
    public boolean checkForNewActivity() {
        int currentCount = getSessionCount();
        int lastCount = lastKnownSessionCount.getAndSet(currentCount);

        if (currentCount > lastCount) {
            recordActivity();
            return true;
        }
        return false;
    }

    /**
     * Gets the current session count from the database.
     */
    public int getSessionCount() {
        String dbUrl = "jdbc:h2:tcp://localhost:" + tcpPort + "/" + dbPath.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            if (verbose) {
                LOG.warning("Failed to get session count: " + e.getMessage());
            }
        }
        return 0;
    }

    // ========== Server Info Getters ==========

    public int getTcpPort() { return tcpPort; }
    public int getHttpPort() { return httpPort; }
    public File getDbPath() { return dbPath; }
    public OffsetDateTime getStartedAt() { return startedAt; }

    // ========== Private Helper Methods ==========

    private void initializeDatabase() throws SQLException {
        String dbUrl = "jdbc:h2:tcp://localhost:" + tcpPort + "/" + dbPath.getAbsolutePath();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            H2LogStore.initSchema(conn);

            // Get initial session count
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
                if (rs.next()) {
                    lastKnownSessionCount.set(rs.getInt(1));
                }
            }

            if (verbose) {
                LOG.info("Database schema initialized: " + dbPath.getAbsolutePath());
            }
        }
    }

    private void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", httpPort), 0);
        httpServer.createContext("/info", this::handleInfoRequest);
        httpServer.setExecutor(null);
        httpServer.start();

        if (verbose) {
            LOG.info("HTTP info server started on port " + httpPort);
        }
    }

    private void handleInfoRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            int sessionCount = getSessionCount();
            int connectionCount = getActiveConnectionCount();

            String json = String.format("""
                {
                  "server": "actor-iac-log-server",
                  "version": "2.11.0",
                  "port": %d,
                  "http_port": %d,
                  "db_path": "%s",
                  "db_file": "%s.mv.db",
                  "started_at": "%s",
                  "session_count": %d,
                  "active_connections": %d,
                  "idle_time_ms": %d
                }
                """,
                tcpPort,
                httpPort,
                escapeJson(dbPath.getAbsolutePath()),
                escapeJson(dbPath.getAbsolutePath()),
                startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                sessionCount,
                connectionCount,
                getIdleTimeMillis()
            );

            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        } catch (Exception e) {
            String error = "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
            byte[] response = error.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
