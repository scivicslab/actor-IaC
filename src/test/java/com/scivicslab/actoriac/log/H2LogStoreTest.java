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

    // ========== Tests for node count derivation from node_results ==========

    @Test
    @DisplayName("Should derive nodeCount from node_results when available")
    void testNodeCountDerivedFromNodeResults() throws InterruptedException {
        // Create session with initial nodeCount=1 (hardcoded in RunCLI)
        long sessionId = logStore.startSession("test-workflow", 1);

        // Mark 3 nodes as successful - this is the actual node count
        logStore.markNodeSuccess(sessionId, "node-192.168.5.21");
        logStore.markNodeSuccess(sessionId, "node-192.168.5.22");
        logStore.markNodeSuccess(sessionId, "node-192.168.5.23");

        // Wait for async writes
        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);

        // nodeCount should be derived from node_results (3), not sessions table (1)
        assertEquals(3, summary.getNodeCount(), "nodeCount should be derived from node_results count");
        assertEquals(3, summary.getSuccessCount(), "successCount should be 3");
        assertEquals(0, summary.getFailedCount(), "failedCount should be 0");
    }

    @Test
    @DisplayName("Should use sessions table nodeCount when node_results is empty")
    void testNodeCountFallbackToSessionsTable() throws InterruptedException {
        // Create session with initial nodeCount=5
        long sessionId = logStore.startSession("test-workflow", 5);

        // Don't mark any nodes - simulates old behavior or workflow that doesn't track nodes
        logStore.log(sessionId, "cli", LogLevel.INFO, "Workflow started");

        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);

        // nodeCount should fall back to sessions table value (5)
        assertEquals(5, summary.getNodeCount(), "nodeCount should fall back to sessions table value");
        assertEquals(0, summary.getSuccessCount(), "successCount should be 0");
        assertEquals(0, summary.getFailedCount(), "failedCount should be 0");
    }

    @Test
    @DisplayName("Should correctly count mixed success and failure nodes")
    void testMixedNodeResults() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        // 2 successes, 2 failures
        logStore.markNodeSuccess(sessionId, "node-web-01");
        logStore.markNodeSuccess(sessionId, "node-web-02");
        logStore.markNodeFailed(sessionId, "node-db-01", "Connection refused");
        logStore.markNodeFailed(sessionId, "node-db-02", "Timeout");

        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);

        assertEquals(4, summary.getNodeCount(), "nodeCount should be total of success + failed");
        assertEquals(2, summary.getSuccessCount(), "successCount should be 2");
        assertEquals(2, summary.getFailedCount(), "failedCount should be 2");
        assertTrue(summary.getFailedNodes().contains("node-db-01"), "failedNodes should contain node-db-01");
        assertTrue(summary.getFailedNodes().contains("node-db-02"), "failedNodes should contain node-db-02");
    }

    @Test
    @DisplayName("Should update node status when marked multiple times")
    void testNodeStatusUpdate() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        // First mark as failed, then as success (retry scenario)
        logStore.markNodeFailed(sessionId, "node-001", "First attempt failed");
        Thread.sleep(100);
        logStore.markNodeSuccess(sessionId, "node-001");

        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);

        // Node should be counted only once, with latest status (SUCCESS)
        assertEquals(1, summary.getNodeCount(), "nodeCount should be 1 (node counted once)");
        assertEquals(1, summary.getSuccessCount(), "successCount should be 1 (latest status)");
        assertEquals(0, summary.getFailedCount(), "failedCount should be 0");
    }

    @Test
    @DisplayName("Session summary toString should show correct Results section")
    void testSessionSummaryToStringShowsResults() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        logStore.markNodeSuccess(sessionId, "node-001");
        logStore.markNodeSuccess(sessionId, "node-002");
        logStore.markNodeFailed(sessionId, "node-003", "Error");

        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);
        String summaryStr = summary.toString();

        assertTrue(summaryStr.contains("Nodes:    3"), "Summary should show Nodes: 3");
        assertTrue(summaryStr.contains("SUCCESS: 2 nodes"), "Summary should show SUCCESS: 2 nodes");
        assertTrue(summaryStr.contains("FAILED:  1 nodes"), "Summary should show FAILED: 1 nodes");
        assertTrue(summaryStr.contains("node-003"), "Summary should list failed node");
    }

    // ========== Tests for node counting behavior ==========

    @Test
    @DisplayName("Only nodes with results should be counted")
    void testOnlyNodesWithResultsAreCounted() throws Exception {
        long sessionId = logStore.startSession("test-workflow", 1);

        // Log from various actors (cli, nodeGroup are internal, node-* are actual nodes)
        logStore.log(sessionId, "cli", LogLevel.INFO, "Starting workflow");
        logStore.log(sessionId, "nodeGroup", LogLevel.INFO, "Creating nodes");
        logStore.log(sessionId, "node-web-01", LogLevel.INFO, "Running task");
        logStore.log(sessionId, "node-web-02", LogLevel.INFO, "Running task");

        // Only mark actual workflow nodes in node_results
        logStore.markNodeSuccess(sessionId, "node-web-01");
        logStore.markNodeSuccess(sessionId, "node-web-02");

        Thread.sleep(200);

        // Verify via getSummary - nodeCount should come from node_results
        SessionSummary summary = logStore.getSummary(sessionId);
        assertEquals(2, summary.getNodeCount(), "Should count only workflow nodes from node_results");
    }

    @Test
    @DisplayName("Node results should include log count per node")
    void testNodeResultsIncludeLogCount() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        // Log different amounts for different nodes
        logStore.log(sessionId, "node-001", LogLevel.INFO, "Message 1");
        logStore.log(sessionId, "node-001", LogLevel.INFO, "Message 2");
        logStore.log(sessionId, "node-001", LogLevel.INFO, "Message 3");
        logStore.log(sessionId, "node-002", LogLevel.INFO, "Message 1");

        logStore.markNodeSuccess(sessionId, "node-001");
        logStore.markNodeSuccess(sessionId, "node-002");

        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        // Verify via log count from getLogsByNode
        List<LogEntry> node1Logs = logStore.getLogsByNode(sessionId, "node-001");
        List<LogEntry> node2Logs = logStore.getLogsByNode(sessionId, "node-002");

        assertEquals(3, node1Logs.size(), "node-001 should have 3 log entries");
        assertEquals(1, node2Logs.size(), "node-002 should have 1 log entry");
    }

    @Test
    @DisplayName("Internal actors (cli, nodeGroup) should not appear in node count")
    void testInternalActorsNotInNodeCount() throws InterruptedException {
        long sessionId = logStore.startSession("test-workflow", 1);

        // Log from internal actors
        logStore.log(sessionId, "cli", LogLevel.INFO, "CLI message");
        logStore.log(sessionId, "nodeGroup", LogLevel.INFO, "NodeGroup message");

        // Log from actual nodes and mark their results
        logStore.log(sessionId, "node-host1", LogLevel.INFO, "Node message");
        logStore.markNodeSuccess(sessionId, "node-host1");

        Thread.sleep(200);

        logStore.endSession(sessionId, SessionStatus.COMPLETED);

        SessionSummary summary = logStore.getSummary(sessionId);

        // Only node-host1 should be counted (cli and nodeGroup are internal)
        assertEquals(1, summary.getNodeCount(), "Only actual workflow nodes should be counted");
        assertEquals(1, summary.getSuccessCount(), "successCount should be 1");

        // Total log entries should include all actors
        assertEquals(3, summary.getTotalLogEntries(), "Total logs should include all actors");
    }
}
