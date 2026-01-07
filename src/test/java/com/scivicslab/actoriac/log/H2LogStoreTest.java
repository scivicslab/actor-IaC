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

package com.scivicslab.actoriac.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for H2LogStore.
 *
 * @author devteam@scivics-lab.com
 */
@DisplayName("H2LogStore Tests")
class H2LogStoreTest {

    private H2LogStore logStore;

    @BeforeEach
    void setUp() throws SQLException {
        // Use in-memory database for testing
        logStore = new H2LogStore();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (logStore != null) {
            logStore.close();
        }
    }

    @Test
    @DisplayName("Should start a session and return valid ID")
    void testStartSession() {
        long sessionId = logStore.startSession("test-workflow", 5);
        assertTrue(sessionId > 0, "Session ID should be positive");
    }

    @Test
    @DisplayName("Should log messages and retrieve by node")
    void testLogAndRetrieveByNode() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 2);

        logStore.log(sessionId, "node-001", LogLevel.INFO, "Starting task");
        logStore.log(sessionId, "node-002", LogLevel.INFO, "Starting task");
        logStore.log(sessionId, "node-001", LogLevel.INFO, "Task completed");

        // Wait for async writes
        Thread.sleep(200);

        List<LogEntry> node1Logs = logStore.getLogsByNode(sessionId, "node-001");
        assertEquals(2, node1Logs.size(), "Node-001 should have 2 log entries");

        List<LogEntry> node2Logs = logStore.getLogsByNode(sessionId, "node-002");
        assertEquals(1, node2Logs.size(), "Node-002 should have 1 log entry");
    }

    @Test
    @DisplayName("Should log actions with exit codes and duration")
    void testLogAction() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        logStore.logAction(sessionId, "node-001", "init", "executeCommand",
                0, 150, "Command output");
        logStore.logAction(sessionId, "node-001", "deploy", "executeCommand",
                1, 50, "Command failed");

        // Wait for async writes
        Thread.sleep(200);

        List<LogEntry> logs = logStore.getLogsByNode(sessionId, "node-001");
        assertEquals(2, logs.size());

        // Check first action (success)
        LogEntry successLog = logs.get(0);
        assertEquals(LogLevel.INFO, successLog.getLevel());
        assertEquals(Integer.valueOf(0), successLog.getExitCode());
        assertEquals(Long.valueOf(150), successLog.getDurationMs());

        // Check second action (failure)
        LogEntry failLog = logs.get(1);
        assertEquals(LogLevel.ERROR, failLog.getLevel());
        assertEquals(Integer.valueOf(1), failLog.getExitCode());
    }

    @Test
    @DisplayName("Should filter logs by level")
    void testGetLogsByLevel() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        logStore.log(sessionId, "node-001", LogLevel.DEBUG, "Debug message");
        logStore.log(sessionId, "node-001", LogLevel.INFO, "Info message");
        logStore.log(sessionId, "node-001", LogLevel.WARN, "Warning message");
        logStore.log(sessionId, "node-001", LogLevel.ERROR, "Error message");

        // Wait for async writes
        Thread.sleep(200);

        List<LogEntry> allLogs = logStore.getLogsByLevel(sessionId, LogLevel.DEBUG);
        assertEquals(4, allLogs.size(), "Should return all logs for DEBUG level");

        List<LogEntry> warnAndAbove = logStore.getLogsByLevel(sessionId, LogLevel.WARN);
        assertEquals(2, warnAndAbove.size(), "Should return WARN and ERROR logs");

        List<LogEntry> errorsOnly = logStore.getLogsByLevel(sessionId, LogLevel.ERROR);
        assertEquals(1, errorsOnly.size(), "Should return only ERROR logs");
    }

    @Test
    @DisplayName("Should track node success and failure status")
    void testNodeResults() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 3);

        logStore.markNodeSuccess(sessionId, "node-001");
        logStore.markNodeFailed(sessionId, "node-002", "Connection refused");
        logStore.markNodeSuccess(sessionId, "node-003");

        // Wait for async writes
        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);
        assertEquals(2, summary.getSuccessCount(), "Should have 2 successful nodes");
        assertEquals(1, summary.getFailedCount(), "Should have 1 failed node");
        assertTrue(summary.getFailedNodes().contains("node-002"), "Failed nodes should include node-002");
    }

    @Test
    @DisplayName("Should generate session summary")
    void testSessionSummary() throws InterruptedException {
        long sessionId = logStore.startSession("deploy-webservers", 10);

        logStore.log(sessionId, "node-001", LogLevel.INFO, "Started");
        logStore.log(sessionId, "node-001", LogLevel.ERROR, "Failed");

        // Wait for async writes
        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);
        assertNotNull(summary);
        assertEquals("deploy-webservers", summary.getWorkflowName());
        assertEquals(10, summary.getNodeCount());
        assertEquals(SessionStatus.COMPLETED, summary.getStatus());
        assertEquals(2, summary.getTotalLogEntries());
        assertEquals(1, summary.getErrorCount());
    }

    @Test
    @DisplayName("Should list recent sessions")
    void testListSessions() {
        logStore.startSession("workflow-1", 1);
        logStore.startSession("workflow-2", 2);
        logStore.startSession("workflow-3", 3);

        List<SessionSummary> sessions = logStore.listSessions(10);
        assertEquals(3, sessions.size(), "Should return 3 sessions");

        // Sessions should be ordered by start time descending
        assertEquals("workflow-3", sessions.get(0).getWorkflowName());
    }

    @Test
    @DisplayName("Should handle concurrent logging from multiple nodes")
    void testConcurrentLogging() throws InterruptedException {
        long sessionId = logStore.startSession("concurrent-test", 100);

        // Simulate 100 nodes logging concurrently
        Thread[] threads = new Thread[100];
        for (int i = 0; i < 100; i++) {
            final int nodeNum = i;
            threads[i] = new Thread(() -> {
                String nodeId = "node-" + String.format("%03d", nodeNum);
                logStore.log(sessionId, nodeId, LogLevel.INFO, "Task started");
                logStore.log(sessionId, nodeId, LogLevel.INFO, "Task completed");
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread t : threads) {
            t.join();
        }

        // Wait for async writes
        Thread.sleep(500);

        // Verify all logs were recorded
        List<LogEntry> logs = logStore.getLogsByLevel(sessionId, LogLevel.DEBUG);
        assertEquals(200, logs.size(), "Should have 200 log entries (2 per node)");
    }

    @Test
    @DisplayName("Should start session with overlay and inventory names")
    void testStartSessionWithOverlayAndInventory() {
        long sessionId = logStore.startSession("deploy-workflow", "production", "servers.ini", 5);
        assertTrue(sessionId > 0, "Session ID should be positive");

        SessionSummary summary = logStore.getSummary(sessionId);
        assertEquals("deploy-workflow", summary.getWorkflowName());
        assertEquals("production", summary.getOverlayName());
        assertEquals("servers.ini", summary.getInventoryName());
        assertEquals(5, summary.getNodeCount());
    }

    @Test
    @DisplayName("Should start session with null overlay and inventory")
    void testStartSessionWithNullOverlayAndInventory() {
        long sessionId = logStore.startSession("local-workflow", null, null, 1);
        assertTrue(sessionId > 0, "Session ID should be positive");

        SessionSummary summary = logStore.getSummary(sessionId);
        assertEquals("local-workflow", summary.getWorkflowName());
        assertNull(summary.getOverlayName());
        assertNull(summary.getInventoryName());
    }

    @Test
    @DisplayName("Should include overlay and inventory in session summary toString")
    void testSessionSummaryToStringWithOverlayAndInventory() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", "staging", "hosts.yml", 2);

        logStore.log(sessionId, "node-001", LogLevel.INFO, "Started");
        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);
        String summaryStr = summary.toString();

        assertTrue(summaryStr.contains("Overlay:  staging"), "Summary should include overlay name");
        assertTrue(summaryStr.contains("Inventory: hosts.yml"), "Summary should include inventory name");
    }
}
