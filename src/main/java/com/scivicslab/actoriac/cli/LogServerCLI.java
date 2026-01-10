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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.h2.tools.Server;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI subcommand to start an H2 TCP server for centralized workflow logging.
 *
 * <p>This enables multiple actor-IaC processes on the same machine to write to
 * a single shared log database. The server runs on localhost only - remote
 * connections are not needed because actor-IaC operates from a single
 * operation terminal.</p>
 *
 * <p><strong>Architecture:</strong></p>
 * <pre>
 * [Operation Terminal]
 * ├── H2 Log Server (localhost:29090)
 * ├── Workflow Process A ──→ TCP write
 * ├── Workflow Process B ──→ TCP write
 * └── Workflow Process C ──→ TCP write
 *          │
 *          │ SSH commands
 *          ▼
 * [Remote Nodes] ← Don't write directly to log server
 * </pre>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>{@code
 * # Start the log server
 * actor-iac log-server --db ./logs/actor-iac-logs
 *
 * # In another terminal, run workflow with --log-server
 * actor-iac -d ./workflows -w deploy --log-server=localhost:29090
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 */
@Command(
    name = "log-serve",
    mixinStandardHelpOptions = true,
    version = "actor-IaC log-serve 2.11.0",
    description = "Serve H2 TCP server for centralized workflow logging."
)
public class LogServerCLI implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(LogServerCLI.class.getName());

    @Option(
        names = {"-p", "--port"},
        description = "TCP port for H2 server (default: ${DEFAULT-VALUE})",
        defaultValue = "29090"
    )
    private int port;

    @Option(
        names = {"--db"},
        description = "Database file path without extension (default: ${DEFAULT-VALUE})",
        defaultValue = "./actor-iac-logs"
    )
    private File dbPath;

    @Option(
        names = {"-v", "--verbose"},
        description = "Enable verbose output"
    )
    private boolean verbose;

    @Option(
        names = {"--find"},
        description = "Find running H2 log servers on localhost and show port status"
    )
    private boolean find;

    @Option(
        names = {"--info-port"},
        description = "HTTP port for info API (default: TCP port + 1000)"
    )
    private Integer infoPort;

    private Server tcpServer;
    private HttpServer httpServer;
    private OffsetDateTime startedAt;

    /** Default offset from TCP port to HTTP port */
    private static final int HTTP_PORT_OFFSET = 1000;

    /** Ports to scan for H2 servers (actor-IaC reserved range: 29090-29100) */
    private static final int[] SCAN_PORTS = {29090, 29091, 29092, 29093, 29094, 29095, 29096, 29097, 29098, 29099, 29100};
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @Override
    public Integer call() {
        // Handle --find option
        if (find) {
            return findLogServers();
        }

        try {
            // Record start time
            startedAt = OffsetDateTime.now();

            // Calculate HTTP port
            int httpPort = (infoPort != null) ? infoPort : port + HTTP_PORT_OFFSET;

            // Build server arguments (localhost only - no remote connections needed)
            String[] serverArgs = new String[]{
                "-tcp",
                "-tcpPort", String.valueOf(port),
                "-ifNotExists"
            };

            // Create and start TCP server
            tcpServer = Server.createTcpServer(serverArgs);
            tcpServer.start();

            // Initialize database schema
            initializeDatabase();

            // Start HTTP info server
            startHttpServer(httpPort);

            // Print connection info
            printConnectionInfo(httpPort);

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "LogServer-Shutdown"));

            System.out.println("\nServer is running. Press Ctrl+C to stop.");

            // Wait for shutdown signal
            shutdownLatch.await();

            return 0;

        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        } catch (Exception e) {
            System.err.println("Failed to start log server: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private void initializeDatabase() throws SQLException {
        String dbUrl = "jdbc:h2:tcp://localhost:" + port + "/" + dbPath.getAbsolutePath();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            // Sessions table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id IDENTITY PRIMARY KEY,
                    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    ended_at TIMESTAMP,
                    workflow_name VARCHAR(255),
                    overlay_name VARCHAR(255),
                    inventory_name VARCHAR(255),
                    node_count INT,
                    status VARCHAR(20) DEFAULT 'RUNNING'
                )
                """);

            // Logs table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    node_id VARCHAR(255) NOT NULL,
                    vertex_name CLOB,
                    action_name CLOB,
                    level VARCHAR(10) NOT NULL,
                    message CLOB,
                    exit_code INT,
                    duration_ms BIGINT,
                    FOREIGN KEY (session_id) REFERENCES sessions(id)
                )
                """);

            // Node results table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS node_results (
                    id IDENTITY PRIMARY KEY,
                    session_id BIGINT,
                    node_id VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    reason VARCHAR(1000),
                    FOREIGN KEY (session_id) REFERENCES sessions(id),
                    UNIQUE (session_id, node_id)
                )
                """);

            // Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_session ON logs(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_node ON logs(node_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_level ON logs(level)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_workflow ON sessions(workflow_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_overlay ON sessions(overlay_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_inventory ON sessions(inventory_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at)");

            if (verbose) {
                System.out.println("Database schema initialized: " + dbPath.getAbsolutePath());
            }
        }
    }

    /**
     * Starts the HTTP server for the /info API endpoint.
     */
    private void startHttpServer(int httpPort) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", httpPort), 0);
        httpServer.createContext("/info", this::handleInfoRequest);
        httpServer.setExecutor(null); // Use default executor
        httpServer.start();

        if (verbose) {
            System.out.println("HTTP info server started on port " + httpPort);
        }
    }

    /**
     * Handles GET /info requests, returning server information as JSON.
     */
    private void handleInfoRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            // Get session count from database
            int sessionCount = getSessionCount();

            // Build JSON response
            String json = String.format("""
                {
                  "server": "actor-iac-log-server",
                  "version": "2.11.0",
                  "port": %d,
                  "db_path": "%s",
                  "db_file": "%s.mv.db",
                  "started_at": "%s",
                  "session_count": %d
                }
                """,
                port,
                escapeJson(dbPath.getAbsolutePath()),
                escapeJson(dbPath.getAbsolutePath()),
                startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                sessionCount
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

    /**
     * Gets the current session count from the database.
     */
    private int getSessionCount() {
        String dbUrl = "jdbc:h2:tcp://localhost:" + port + "/" + dbPath.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            if (verbose) {
                System.err.println("Failed to get session count: " + e.getMessage());
            }
        }
        return 0;
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private void printConnectionInfo(int httpPort) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("H2 Log Server Started");
        System.out.println("=".repeat(60));
        System.out.println("  TCP Port:   " + port);
        System.out.println("  HTTP Port:  " + httpPort);
        System.out.println("  Database:   " + dbPath.getAbsolutePath() + ".mv.db");
        System.out.println();
        System.out.println("Connect from workflows using:");
        System.out.println("  --log-serve=localhost:" + port);
        System.out.println();
        System.out.println("Server info API:");
        System.out.println("  curl http://localhost:" + httpPort + "/info");
        System.out.println();
        System.out.println("Query logs using:");
        System.out.println("  actor-iac log-search --server=localhost:" + port + " --db " + dbPath.getAbsolutePath() + " --list");
        System.out.println("=".repeat(60));
    }

    private void shutdown() {
        System.out.println("\nShutting down servers...");
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (tcpServer != null) {
            tcpServer.stop();
        }
        shutdownLatch.countDown();
        System.out.println("Servers stopped.");
    }

    /**
     * Finds running H2 log servers on localhost.
     *
     * @return exit code (0 = success)
     */
    private Integer findLogServers() {
        System.out.println("=".repeat(60));
        System.out.println("Scanning for H2 Log Servers on localhost...");
        System.out.println("=".repeat(60));
        System.out.println();

        List<ServerInfo> h2Servers = new ArrayList<>();
        List<ServerInfo> otherServices = new ArrayList<>();

        for (int scanPort : SCAN_PORTS) {
            ServerInfo info = checkPort(scanPort);
            if (info != null) {
                if (info.isH2Server) {
                    h2Servers.add(info);
                } else {
                    otherServices.add(info);
                }
            }
        }

        // Report H2 log servers found
        if (h2Servers.isEmpty()) {
            System.out.println("No H2 log servers found.");
            System.out.println();
            System.out.println("To start a log server:");
            System.out.println("  ./actor_iac.java log-serve --db ./logs/actor-iac-logs");
        } else {
            System.out.println("H2 Log Servers Found:");
            System.out.println("-".repeat(60));
            for (ServerInfo info : h2Servers) {
                System.out.println("  Port " + info.port + ": H2 Database Server");
                if (info.hasHttpApi) {
                    System.out.println("           HTTP API: http://localhost:" + info.httpPort + "/info");
                    if (info.version != null) {
                        System.out.println("           Version:  " + info.version);
                    }
                }
                if (info.dbPath != null) {
                    System.out.println("           Database: " + info.dbPath);
                }
                if (info.sessionCount >= 0) {
                    System.out.println("           Sessions: " + info.sessionCount);
                }
                if (info.startedAt != null) {
                    System.out.println("           Started:  " + info.startedAt);
                }
                System.out.println("           Connect:  --log-serve=localhost:" + info.port);
                System.out.println();
            }
        }

        // Report other services on nearby ports
        if (!otherServices.isEmpty()) {
            System.out.println();
            System.out.println("Other Services on Nearby Ports:");
            System.out.println("-".repeat(60));
            for (ServerInfo info : otherServices) {
                System.out.println("  Port " + info.port + ": " + info.serviceName);
                if (info.processInfo != null) {
                    System.out.println("           Process: " + info.processInfo);
                }
            }
        }

        // Suggest available port
        int availablePort = findAvailablePort();
        if (availablePort > 0 && h2Servers.isEmpty()) {
            System.out.println();
            System.out.println("All ports in range 29090-29100 are available.");
            System.out.println("Start a log server with:");
            System.out.println("  ./actor_iac.java log-serve --db ./logs/actor-iac-logs");
        } else if (availablePort > 0) {
            System.out.println();
            System.out.println("Next available port: " + availablePort);
            System.out.println("  ./actor_iac.java log-serve --port " + availablePort + " --db ./logs/actor-iac-logs");
        }

        System.out.println();
        System.out.println("=".repeat(60));

        return 0;
    }

    /**
     * Checks what's running on a port.
     */
    private ServerInfo checkPort(int checkPort) {
        // First, check if port is open
        try (Socket socket = new Socket("localhost", checkPort)) {
            socket.setSoTimeout(1000);
            // Port is open, try to identify the service
        } catch (Exception e) {
            // Port not open
            return null;
        }

        ServerInfo info = new ServerInfo(checkPort);

        // Try to connect as H2
        try {
            // Try to connect to H2 and query session count
            String url = "jdbc:h2:tcp://localhost:" + checkPort + "/~/.h2/test;IFEXISTS=TRUE";
            try (Connection conn = DriverManager.getConnection(url)) {
                info.isH2Server = true;
                // This DB might not have our schema, but we know it's H2
            }
        } catch (SQLException e) {
            // Check if it's "Database not found" - that still means H2 server is running
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Database") || msg.contains("not found") ||
                    msg.contains("Connection is broken") || msg.contains("90067"))) {
                info.isH2Server = true;
            }
        }

        if (info.isH2Server) {
            // Try to get info from actor-iac-logs database
            tryGetLogServerInfo(info);
        } else {
            // Try to identify other services using lsof
            info.serviceName = identifyService(checkPort);
        }

        return info;
    }

    /**
     * Tries to get information about a log server's database.
     * First tries HTTP API, then falls back to database queries.
     */
    private void tryGetLogServerInfo(ServerInfo info) {
        // First, try HTTP API (port + 1000)
        if (tryGetInfoFromHttpApi(info, info.port + HTTP_PORT_OFFSET)) {
            return;
        }

        // Fallback: try common database paths via JDBC
        String[] commonPaths = {
            "./actor-iac-logs",
            "./logs/actor-iac-logs",
            System.getProperty("user.home") + "/actor-iac-logs"
        };

        for (String path : commonPaths) {
            try {
                String url = "jdbc:h2:tcp://localhost:" + info.port + "/" + path;
                try (Connection conn = DriverManager.getConnection(url);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
                    if (rs.next()) {
                        info.sessionCount = rs.getInt(1);
                        info.dbPath = path;
                        return;
                    }
                }
            } catch (SQLException e) {
                // This path doesn't have our schema, try next
            }
        }
    }

    /**
     * Tries to get server info from HTTP API.
     *
     * @return true if successfully got info from API
     */
    private boolean tryGetInfoFromHttpApi(ServerInfo info, int httpPort) {
        try {
            java.net.URL url = new java.net.URL("http://localhost:" + httpPort + "/info");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }

                    // Parse JSON manually (avoid dependency)
                    String jsonStr = json.toString();
                    info.hasHttpApi = true;
                    info.httpPort = httpPort;
                    info.dbPath = extractJsonString(jsonStr, "db_path");
                    info.version = extractJsonString(jsonStr, "version");
                    info.startedAt = extractJsonString(jsonStr, "started_at");
                    info.sessionCount = extractJsonInt(jsonStr, "session_count");
                    return true;
                }
            }
        } catch (Exception e) {
            // HTTP API not available
        }
        return false;
    }

    /**
     * Extracts a string value from JSON.
     */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts an integer value from JSON.
     */
    private int extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Identifies a service using lsof.
     */
    private String identifyService(int checkPort) {
        try {
            ProcessBuilder pb = new ProcessBuilder("lsof", "-i", ":" + checkPort, "-sTCP:LISTEN");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("COMMAND")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 0) {
                            return parts[0]; // Process name
                        }
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            // lsof not available or failed
        }

        return "Unknown service";
    }

    /**
     * Finds an available port.
     */
    private int findAvailablePort() {
        for (int scanPort : SCAN_PORTS) {
            try (Socket socket = new Socket("localhost", scanPort)) {
                // Port is in use
            } catch (Exception e) {
                // Port is available
                return scanPort;
            }
        }
        return -1;
    }

    /**
     * Information about a service running on a port.
     */
    private static class ServerInfo {
        final int port;
        boolean isH2Server = false;
        boolean hasHttpApi = false;
        int httpPort = -1;
        String dbPath = null;
        int sessionCount = -1;
        String startedAt = null;
        String version = null;
        String serviceName = null;
        String processInfo = null;

        ServerInfo(int port) {
            this.port = port;
        }
    }
}
