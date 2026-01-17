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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI tool for clearing (deleting) the H2 log database files.
 *
 * <p>This command safely deletes the database files after checking
 * that the log server is not running.</p>
 *
 * <p>Usage examples:</p>
 * <pre>
 * # Clear the default database
 * java -jar actor-IaC.jar db-clear --db logs
 *
 * # Force clear without checking log server
 * java -jar actor-IaC.jar db-clear --db logs --force
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
@Command(
    name = "db-clear",
    mixinStandardHelpOptions = true,
    version = "actor-IaC db-clear 2.12.0",
    description = "Clear (delete) the H2 log database files."
)
public class DbClearCLI implements Callable<Integer> {

    @Option(
        names = {"--db"},
        description = "H2 database path (without .mv.db extension)",
        required = true
    )
    private File dbPath;

    @Option(
        names = {"--http-port"},
        description = "HTTP port to check for running log server",
        defaultValue = "29091"
    )
    private int httpPort;

    @Option(
        names = {"-f", "--force"},
        description = "Force clear without checking if log server is running"
    )
    private boolean force;

    /**
     * Main entry point for the db-clear CLI.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new DbClearCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // Check if log server is running
        if (!force && isLogServerRunning()) {
            System.err.println("Error: Log server is running on HTTP port " + httpPort);
            System.err.println("Please stop the log server first, or use --force to skip this check.");
            return 1;
        }

        File mvDb = new File(dbPath.getAbsolutePath() + ".mv.db");
        File traceDb = new File(dbPath.getAbsolutePath() + ".trace.db");

        boolean anyDeleted = false;

        if (mvDb.exists()) {
            if (mvDb.delete()) {
                System.out.println("Deleted: " + mvDb.getAbsolutePath());
                anyDeleted = true;
            } else {
                System.err.println("Failed to delete: " + mvDb.getAbsolutePath());
                return 1;
            }
        }

        if (traceDb.exists()) {
            if (traceDb.delete()) {
                System.out.println("Deleted: " + traceDb.getAbsolutePath());
                anyDeleted = true;
            } else {
                System.err.println("Failed to delete: " + traceDb.getAbsolutePath());
                return 1;
            }
        }

        if (!anyDeleted) {
            System.out.println("No database files found at: " + dbPath.getAbsolutePath());
        } else {
            System.out.println("Database cleared successfully.");
        }

        return 0;
    }

    /**
     * Checks if the log server is running by attempting to connect to the HTTP info endpoint.
     *
     * @return true if log server is running
     */
    private boolean isLogServerRunning() {
        try {
            URL url = new URL("http://localhost:" + httpPort + "/info");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (IOException e) {
            // Connection failed, server is not running
            return false;
        }
    }
}
