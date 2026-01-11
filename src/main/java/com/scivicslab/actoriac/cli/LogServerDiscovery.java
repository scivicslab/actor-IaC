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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers running H2 log servers that match a specific database path.
 *
 * <p>This class scans the actor-IaC reserved port range (29090-29100) and uses the
 * HTTP info API (TCP port - 200) to find servers that are serving a specific database.</p>
 *
 * <p><strong>Discovery Flow:</strong></p>
 * <pre>
 * 1. Check if target DB file exists
 * 2. Scan HTTP ports 28890-28900 for /info API
 * 3. Compare db_path from API with target path
 * 4. Return matching server's TCP port if found
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.11.0
 */
public class LogServerDiscovery {

    private static final Logger LOG = Logger.getLogger(LogServerDiscovery.class.getName());

    /** TCP port range for H2 servers */
    private static final int TCP_PORT_START = 29090;
    private static final int TCP_PORT_END = 29100;

    /** Offset from TCP port to HTTP port (TCP - 200 = HTTP) */
    private static final int HTTP_PORT_OFFSET = -200;

    /** Connection timeout in milliseconds */
    private static final int CONNECT_TIMEOUT = 1000;

    /** Read timeout in milliseconds */
    private static final int READ_TIMEOUT = 1000;

    /**
     * Result of a server discovery attempt.
     */
    public static class DiscoveryResult {
        private final boolean found;
        private final int tcpPort;
        private final int httpPort;
        private final String dbPath;
        private final String version;
        private final int sessionCount;

        private DiscoveryResult(boolean found, int tcpPort, int httpPort,
                               String dbPath, String version, int sessionCount) {
            this.found = found;
            this.tcpPort = tcpPort;
            this.httpPort = httpPort;
            this.dbPath = dbPath;
            this.version = version;
            this.sessionCount = sessionCount;
        }

        public static DiscoveryResult notFound() {
            return new DiscoveryResult(false, -1, -1, null, null, -1);
        }

        public static DiscoveryResult found(int tcpPort, int httpPort,
                                            String dbPath, String version, int sessionCount) {
            return new DiscoveryResult(true, tcpPort, httpPort, dbPath, version, sessionCount);
        }

        public boolean isFound() { return found; }
        public int getTcpPort() { return tcpPort; }
        public int getHttpPort() { return httpPort; }
        public String getDbPath() { return dbPath; }
        public String getVersion() { return version; }
        public int getSessionCount() { return sessionCount; }

        public String getServerAddress() {
            return "localhost:" + tcpPort;
        }
    }

    /**
     * Discovers a running log server that matches the given database path.
     *
     * @param targetDbPath the database path to match (without .mv.db extension)
     * @return discovery result containing server info if found
     */
    public DiscoveryResult discoverServer(Path targetDbPath) {
        String targetPathStr = targetDbPath.toAbsolutePath().toString();
        LOG.fine("Searching for log server with db_path: " + targetPathStr);

        // Scan HTTP ports for matching server
        for (int tcpPort = TCP_PORT_START; tcpPort <= TCP_PORT_END; tcpPort++) {
            int httpPort = tcpPort + HTTP_PORT_OFFSET;

            Optional<ServerInfo> info = queryServerInfo(httpPort);
            if (info.isPresent()) {
                ServerInfo serverInfo = info.get();
                LOG.fine("Found server at port " + tcpPort + " with db_path: " + serverInfo.dbPath);

                // Compare paths
                if (pathsMatch(targetPathStr, serverInfo.dbPath)) {
                    LOG.info("Auto-detected log server at localhost:" + tcpPort +
                            " (db: " + serverInfo.dbPath + ")");
                    return DiscoveryResult.found(
                            tcpPort, httpPort,
                            serverInfo.dbPath,
                            serverInfo.version,
                            serverInfo.sessionCount
                    );
                }
            }
        }

        LOG.fine("No matching log server found for: " + targetPathStr);
        return DiscoveryResult.notFound();
    }

    /**
     * Queries server info from the HTTP API.
     */
    private Optional<ServerInfo> queryServerInfo(int httpPort) {
        try {
            // First check if port is open
            try (Socket socket = new Socket("localhost", httpPort)) {
                socket.setSoTimeout(CONNECT_TIMEOUT);
            }

            // Query the /info API
            URL url = new URL("http://localhost:" + httpPort + "/info");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }

                    String jsonStr = json.toString();
                    ServerInfo info = new ServerInfo();
                    info.dbPath = extractJsonString(jsonStr, "db_path");
                    info.version = extractJsonString(jsonStr, "version");
                    info.sessionCount = extractJsonInt(jsonStr, "session_count");
                    return Optional.of(info);
                }
            }
        } catch (Exception e) {
            // Server not available or not responding
            LOG.finest("No server at HTTP port " + httpPort + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Compares two paths for equality, handling different path formats.
     */
    private boolean pathsMatch(String path1, String path2) {
        if (path1 == null || path2 == null) {
            return false;
        }

        // Normalize paths for comparison
        try {
            Path p1 = Path.of(path1).toAbsolutePath().normalize();
            Path p2 = Path.of(path2).toAbsolutePath().normalize();
            return p1.equals(p2);
        } catch (Exception e) {
            // Fall back to string comparison
            return path1.equals(path2);
        }
    }

    /**
     * Extracts a string value from JSON.
     */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts an integer value from JSON.
     */
    private int extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Internal class to hold server info from API.
     */
    private static class ServerInfo {
        String dbPath;
        String version;
        int sessionCount;
    }
}
