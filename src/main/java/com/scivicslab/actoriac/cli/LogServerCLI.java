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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
 * ├── H2 Log Server (localhost:9092)
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
 * actor-iac -d ./workflows -w deploy --log-server=localhost:9092
 * }</pre>
 *
 * @author devteam@scivics-lab.com
 */
@Command(
    name = "log-server",
    mixinStandardHelpOptions = true,
    version = "actor-IaC log-server 2.10.0",
    description = "Start H2 TCP server for centralized workflow logging."
)
public class LogServerCLI implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(LogServerCLI.class.getName());

    @Option(
        names = {"-p", "--port"},
        description = "TCP port for H2 server (default: ${DEFAULT-VALUE})",
        defaultValue = "9092"
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

    private Server tcpServer;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @Override
    public Integer call() {
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
}
