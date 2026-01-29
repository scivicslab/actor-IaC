/*
 * Copyright 2025 devteam@scivicslab.com
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

package com.scivicslab.actoriac.report.sections.basic;

import com.scivicslab.actoriac.report.SectionBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * POJO section builder that collects and outputs messages with % prefix.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link CheckResultsSectionIIAR} to expose as an actor.</p>
 *
 * <p>The % prefix is used in workflows to mark lines that should be
 * collected and displayed in the final report. This is useful for
 * check/status reporting:</p>
 *
 * <h2>Usage in workflows:</h2>
 * <pre>
 * - actor: this
 *   method: executeCommand
 *   arguments:
 *     - |
 *       if command -v node > /dev/null; then
 *         echo "%[OK] Node.js: $(node --version)"
 *       else
 *         echo "%[ERROR] Node.js: not found"
 *       fi
 * </pre>
 *
 * <h2>Output example:</h2>
 * <pre>
 * [Check Results]
 * [OK] Node.js: v18.0.0
 * [OK] yarn: 1.22.19
 * [ERROR] Maven: not found
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class CheckResultsSection implements SectionBuilder {

    private static final Logger logger = Logger.getLogger(CheckResultsSection.class.getName());

    /** Prefix for messages to be included in the report. */
    private static final String REPORT_PREFIX = "%";

    private Connection connection;
    private long sessionId = -1;

    /**
     * Sets the database connection for log queries.
     *
     * @param connection the JDBC connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sets the session ID to query logs from.
     *
     * @param sessionId the session ID
     */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String generate() {
        if (connection == null || sessionId < 0) {
            logger.warning("CheckResultsSection: connection or sessionId not set");
            return "";
        }

        try {
            List<String> messages = getReportMessages();
            if (messages.isEmpty()) {
                return "";  // No check results, skip this section
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[Check Results]\n");
            for (String msg : messages) {
                sb.append(msg).append("\n");
            }
            return sb.toString();

        } catch (SQLException e) {
            logger.warning("CheckResultsSection: SQL error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Get messages with % prefix from logs.
     *
     * @return list of messages (without the % prefix)
     * @throws SQLException if database query fails
     */
    private List<String> getReportMessages() throws SQLException {
        List<String> messages = new ArrayList<>();

        String sql = "SELECT message FROM logs WHERE session_id = ? ORDER BY timestamp";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String message = rs.getString("message");
                    if (message != null) {
                        // Extract lines starting with %
                        for (String line : message.split("\n")) {
                            String trimmed = line.trim();
                            // Handle prefixes like [node-xxx] %message
                            String cleaned = trimmed.replaceFirst("^\\[node-[^\\]]+\\]\\s*", "");
                            if (cleaned.startsWith(REPORT_PREFIX)) {
                                messages.add(cleaned.substring(1).trim());
                            }
                        }
                    }
                }
            }
        }

        // Remove duplicates while preserving order, then sort
        List<String> uniqueMessages = new ArrayList<>(new LinkedHashSet<>(messages));
        uniqueMessages.sort(null);
        return uniqueMessages;
    }

    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }
}
