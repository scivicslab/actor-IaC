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

package com.scivicslab.actoriac;

import com.scivicslab.actoriac.log.DistributedLogStore;
import com.scivicslab.actoriac.log.H2LogStore;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for database connection sharing functionality.
 *
 * <p>These tests verify the implementation documented in
 * 028_DatabaseConnection_260127_oo01.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 */
class DatabaseConnectionTest {

    @AfterEach
    void cleanupSingleton() {
        // Always clear singleton after each test
        DistributedLogStore.setInstance(null);
    }

    /**
     * Tests for DistributedLogStore singleton pattern.
     */
    @Nested
    @DisplayName("DistributedLogStore Singleton")
    class DistributedLogStoreSingletonTest {

        @Test
        @DisplayName("getInstance should return null when not set")
        void getInstanceShouldReturnNullWhenNotSet() {
            // Ensure singleton is cleared
            DistributedLogStore.setInstance(null);

            assertNull(DistributedLogStore.getInstance(),
                "getInstance should return null when singleton is not set");
        }

        @Test
        @DisplayName("setInstance should set the singleton")
        void setInstanceShouldSetSingleton() throws Exception {
            H2LogStore logStore = new H2LogStore();

            DistributedLogStore.setInstance(logStore);

            assertSame(logStore, DistributedLogStore.getInstance(),
                "getInstance should return the set instance");

            logStore.close();
        }

        @Test
        @DisplayName("setInstance(null) should clear the singleton")
        void setInstanceNullShouldClearSingleton() throws Exception {
            H2LogStore logStore = new H2LogStore();
            DistributedLogStore.setInstance(logStore);

            // Clear singleton
            DistributedLogStore.setInstance(null);

            assertNull(DistributedLogStore.getInstance(),
                "getInstance should return null after setInstance(null)");

            logStore.close();
        }

        @Test
        @DisplayName("Singleton should be replaceable")
        void singletonShouldBeReplaceable() throws Exception {
            H2LogStore logStore1 = new H2LogStore();
            H2LogStore logStore2 = new H2LogStore();

            DistributedLogStore.setInstance(logStore1);
            assertSame(logStore1, DistributedLogStore.getInstance());

            DistributedLogStore.setInstance(logStore2);
            assertSame(logStore2, DistributedLogStore.getInstance(),
                "Singleton should be replaced when setInstance is called again");

            logStore1.close();
            logStore2.close();
        }
    }

    /**
     * Tests for H2LogStore.getConnection().
     */
    @Nested
    @DisplayName("H2LogStore getConnection")
    class H2LogStoreGetConnectionTest {

        private H2LogStore logStore;

        @BeforeEach
        void setUp() throws SQLException {
            logStore = new H2LogStore();
        }

        @AfterEach
        void tearDown() throws Exception {
            if (logStore != null) {
                logStore.close();
            }
        }

        @Test
        @DisplayName("getConnection should return non-null connection")
        void getConnectionShouldReturnNonNull() {
            Connection connection = logStore.getConnection();

            assertNotNull(connection, "getConnection should return non-null connection");
        }

        @Test
        @DisplayName("getConnection should return the same connection instance")
        void getConnectionShouldReturnSameInstance() {
            Connection connection1 = logStore.getConnection();
            Connection connection2 = logStore.getConnection();

            assertSame(connection1, connection2,
                "getConnection should return the same connection instance");
        }

        @Test
        @DisplayName("Connection should be valid")
        void connectionShouldBeValid() throws SQLException {
            Connection connection = logStore.getConnection();

            assertFalse(connection.isClosed(), "Connection should not be closed");
            assertTrue(connection.isValid(1), "Connection should be valid");
        }
    }

    /**
     * Tests for WorkflowReporter auto-initialization.
     */
    @Nested
    @DisplayName("WorkflowReporter Auto-Initialization")
    class WorkflowReporterAutoInitTest {

        private H2LogStore logStore;
        private IIActorSystem system;

        @BeforeEach
        void setUp() throws SQLException {
            logStore = new H2LogStore();
            system = new IIActorSystem("test-system");
        }

        @AfterEach
        void tearDown() throws Exception {
            DistributedLogStore.setInstance(null);
            if (system != null) {
                system.terminate();
            }
            if (logStore != null) {
                logStore.close();
            }
        }

        @Test
        @DisplayName("WorkflowReporter should auto-initialize connection when singleton is set")
        void shouldAutoInitializeConnectionWhenSingletonIsSet() {
            // Set singleton before creating WorkflowReporter
            DistributedLogStore.setInstance(logStore);

            // Start a session so report has something to query
            long sessionId = logStore.startSession("test-workflow", 1);

            // Create WorkflowReporter and set actor system (triggers auto-init)
            WorkflowReporter reporter = new WorkflowReporter();
            reporter.setActorSystem(system);

            // report action should succeed (connection is available)
            // Pass session ID directly since we don't have nodeGroup
            ActionResult result = reporter.callByActionName("report", String.valueOf(sessionId));

            assertTrue(result.isSuccess(),
                "report should succeed when connection is auto-initialized");
        }

        @Test
        @DisplayName("WorkflowReporter should fail gracefully when singleton is not set")
        void shouldFailGracefullyWhenSingletonNotSet() {
            // Don't set singleton
            DistributedLogStore.setInstance(null);

            // Create WorkflowReporter and set actor system
            WorkflowReporter reporter = new WorkflowReporter();
            reporter.setActorSystem(system);

            // report action should fail (connection is null)
            ActionResult result = reporter.callByActionName("report", "");

            assertFalse(result.isSuccess(),
                "report should fail when connection is not available");
            assertTrue(result.getResult().contains("Not connected"),
                "Error message should mention connection issue");
        }

        @Test
        @DisplayName("WorkflowReporter should use manually set connection over singleton")
        void shouldUseManuallySetConnectionOverSingleton() throws Exception {
            // Create two different logStores
            H2LogStore manualLogStore = new H2LogStore();

            try {
                // Set one as singleton
                DistributedLogStore.setInstance(logStore);

                // Start session on manual logStore
                long sessionId = manualLogStore.startSession("manual-test", 1);

                // Create WorkflowReporter and manually set different connection
                WorkflowReporter reporter = new WorkflowReporter();
                reporter.setConnection(manualLogStore.getConnection());

                // Now set actor system - should NOT override manually set connection
                reporter.setActorSystem(system);

                // report should succeed using manual connection
                // Pass session ID directly since we don't have nodeGroup
                ActionResult result = reporter.callByActionName("report", String.valueOf(sessionId));

                assertTrue(result.isSuccess(),
                    "report should succeed with manually set connection");
            } finally {
                manualLogStore.close();
            }
        }

        @Test
        @DisplayName("Multiple WorkflowReporters should share the same connection")
        void multipleReportersShouldShareConnection() {
            DistributedLogStore.setInstance(logStore);

            // Start session first
            long sessionId = logStore.startSession("shared-test", 1);

            WorkflowReporter reporter1 = new WorkflowReporter();
            WorkflowReporter reporter2 = new WorkflowReporter();

            reporter1.setActorSystem(system);
            reporter2.setActorSystem(system);

            // Both should be able to report
            // Pass session ID directly since we don't have nodeGroup
            ActionResult result1 = reporter1.callByActionName("report", String.valueOf(sessionId));
            ActionResult result2 = reporter2.callByActionName("report", String.valueOf(sessionId));

            assertTrue(result1.isSuccess(), "First reporter should succeed");
            assertTrue(result2.isSuccess(), "Second reporter should succeed");
        }
    }

    /**
     * Tests for connection lifecycle management.
     */
    @Nested
    @DisplayName("Connection Lifecycle")
    class ConnectionLifecycleTest {

        @Test
        @DisplayName("Connection should remain valid after singleton is cleared")
        void connectionShouldRemainValidAfterSingletonCleared() throws Exception {
            H2LogStore logStore = new H2LogStore();
            DistributedLogStore.setInstance(logStore);

            // Get connection via singleton
            Connection connection = DistributedLogStore.getInstance().getConnection();

            // Clear singleton (simulating end of workflow)
            DistributedLogStore.setInstance(null);

            // Connection should still be valid (owned by logStore, not singleton)
            assertFalse(connection.isClosed(),
                "Connection should remain open after singleton is cleared");

            // Only close when logStore is closed
            logStore.close();
            assertTrue(connection.isClosed(),
                "Connection should be closed when logStore is closed");
        }

        @Test
        @DisplayName("Singleton lifecycle should follow RunCLI pattern")
        void singletonLifecycleShouldFollowRunCLIPattern() throws Exception {
            // Simulate RunCLI startup
            H2LogStore logStore = new H2LogStore();
            DistributedLogStore.setInstance(logStore);

            // Start session first
            long sessionId = logStore.startSession("lifecycle-test", 1);

            // Simulate workflow execution with WorkflowReporter
            IIActorSystem system = new IIActorSystem("test");
            WorkflowReporter reporter = new WorkflowReporter();
            reporter.setActorSystem(system);

            // Pass session ID directly since we don't have nodeGroup
            ActionResult midResult = reporter.callByActionName("report", String.valueOf(sessionId));
            assertTrue(midResult.isSuccess(), "Report should work during workflow");

            // Simulate RunCLI cleanup (clear singleton, then close)
            DistributedLogStore.setInstance(null);
            logStore.close();
            system.terminate();

            // Singleton should now be null
            assertNull(DistributedLogStore.getInstance(),
                "Singleton should be null after cleanup");
        }
    }
}
