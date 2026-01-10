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
import java.io.InputStreamReader;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

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
 * <h3>Architecture</h3>
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
 * <h3>Usage</h3>
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
    version = "actor-IaC log-serve 2.10.0",
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

    private Server tcpServer;

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

            // Print connection info
            printConnectionInfo();

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

    private void printConnectionInfo() {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("H2 Log Server Started");
        System.out.println("=".repeat(60));
        System.out.println("  TCP Port:  " + port);
        System.out.println("  Database:  " + dbPath.getAbsolutePath() + ".mv.db");
        System.out.println();
        System.out.println("Connect from workflows using:");
        System.out.println("  --log-server=localhost:" + port);
        System.out.println();
        System.out.println("Query logs using:");
        System.out.println("  actor-iac logs --server=localhost:" + port + " --db " + dbPath.getAbsolutePath() + " --list");
        System.out.println("=".repeat(60));
    }

    private void shutdown() {
        System.out.println("\nShutting down H2 server...");
        if (tcpServer != null) {
            tcpServer.stop();
        }
        shutdownLatch.countDown();
        System.out.println("Server stopped.");
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
            System.out.println("  ./actor_iac.java log-server --db ./logs/actor-iac-logs");
        } else {
            System.out.println("H2 Log Servers Found:");
            System.out.println("-".repeat(60));
            for (ServerInfo info : h2Servers) {
                System.out.println("  Port " + info.port + ": H2 Database Server");
                if (info.dbPath != null) {
                    System.out.println("           Database: " + info.dbPath);
                }
                if (info.sessionCount >= 0) {
                    System.out.println("           Sessions: " + info.sessionCount);
                }
                System.out.println("           Connect:  --log-server=localhost:" + info.port);
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
            System.out.println("  ./actor_iac.java log-server --db ./logs/actor-iac-logs");
        } else if (availablePort > 0) {
            System.out.println();
            System.out.println("Next available port: " + availablePort);
            System.out.println("  ./actor_iac.java log-server --port " + availablePort + " --db ./logs/actor-iac-logs");
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
     */
    private void tryGetLogServerInfo(ServerInfo info) {
        // Try common database paths
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
        String dbPath = null;
        int sessionCount = -1;
        String serviceName = null;
        String processInfo = null;

        ServerInfo(int port) {
            this.port = port;
        }
    }
}
