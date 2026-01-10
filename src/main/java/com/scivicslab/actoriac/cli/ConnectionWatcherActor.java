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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Actor that monitors the log server and triggers auto-shutdown when idle.
 *
 * <p>This actor periodically checks the log server's status and decides
 * whether to shut it down based on:</p>
 * <ul>
 *   <li>Number of active TCP connections</li>
 *   <li>Time since last activity (session creation, log writes)</li>
 * </ul>
 *
 * <p><strong>Shutdown Decision Logic:</strong></p>
 * <pre>
 * Every check interval:
 *   1. Check for new activity (session count changes)
 *   2. Get active connection count
 *   3. If connections == 0 AND idle time > threshold:
 *      → Send stop message to LogServerActor
 * </pre>
 *
 * @author devteam@scivics-lab.com
 * @since 2.11.0
 */
public class ConnectionWatcherActor {

    private static final Logger LOG = Logger.getLogger(ConnectionWatcherActor.class.getName());

    /** Default check interval: 5 minutes */
    public static final long DEFAULT_CHECK_INTERVAL_SECONDS = 300;

    /** Default idle threshold: 5 minutes with no connections */
    public static final long DEFAULT_IDLE_THRESHOLD_SECONDS = 300;

    /** Minimum uptime before auto-shutdown is allowed (to avoid immediate shutdown on startup) */
    public static final long MINIMUM_UPTIME_SECONDS = 30;

    private final ActorRef<LogServerActor> logServerActor;
    private final long checkIntervalSeconds;
    private final long idleThresholdSeconds;
    private final boolean verbose;

    private ScheduledFuture<?> watcherTask;
    private volatile boolean running = false;

    /**
     * Creates a new ConnectionWatcherActor with default settings.
     *
     * @param logServerActor reference to the log server actor
     */
    public ConnectionWatcherActor(ActorRef<LogServerActor> logServerActor) {
        this(logServerActor, DEFAULT_CHECK_INTERVAL_SECONDS, DEFAULT_IDLE_THRESHOLD_SECONDS, false);
    }

    /**
     * Creates a new ConnectionWatcherActor with custom settings.
     *
     * @param logServerActor reference to the log server actor
     * @param checkIntervalSeconds interval between checks in seconds
     * @param idleThresholdSeconds idle time threshold in seconds
     * @param verbose enable verbose logging
     */
    public ConnectionWatcherActor(ActorRef<LogServerActor> logServerActor,
                                  long checkIntervalSeconds,
                                  long idleThresholdSeconds,
                                  boolean verbose) {
        this.logServerActor = logServerActor;
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.idleThresholdSeconds = idleThresholdSeconds;
        this.verbose = verbose;
    }

    /**
     * Starts the watcher using the provided scheduler.
     *
     * @param scheduler the scheduler to use for periodic checks
     */
    public void start(ScheduledExecutorService scheduler) {
        if (running) {
            return;
        }

        running = true;

        // Schedule periodic checks
        watcherTask = scheduler.scheduleAtFixedRate(
            this::checkAndDecide,
            checkIntervalSeconds,  // Initial delay (let server warm up)
            checkIntervalSeconds,  // Period
            TimeUnit.SECONDS
        );

        LOG.info("ConnectionWatcher started (check every " + checkIntervalSeconds + "s, " +
                 "idle threshold " + idleThresholdSeconds + "s)");
    }

    /**
     * Stops the watcher.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        if (watcherTask != null) {
            watcherTask.cancel(false);
        }

        if (verbose) {
            LOG.info("ConnectionWatcher stopped");
        }
    }

    /**
     * Performs a single check and decides whether to shut down the server.
     * This method is called periodically by the scheduler.
     */
    public void checkAndDecide() {
        if (!running) {
            return;
        }

        try {
            // Use actor's ask() to safely access the POJO
            logServerActor.ask(server -> {
                performCheck(server);
                return null;
            }).join();
        } catch (Exception e) {
            LOG.warning("Error during connection check: " + e.getMessage());
        }
    }

    /**
     * Performs the actual check logic.
     * This runs inside the actor's context.
     */
    private void performCheck(LogServerActor server) {
        if (!server.isRunning()) {
            stop();
            return;
        }

        // Check for new activity
        boolean hasNewActivity = server.checkForNewActivity();

        // Get connection count
        int connectionCount = server.getActiveConnectionCount();

        // Get idle time (time since last activity)
        long idleTimeMs = server.getIdleTimeMillis();
        long idleTimeSeconds = idleTimeMs / 1000;

        // Get uptime (time since server started)
        long uptimeSeconds = java.time.Duration.between(
            server.getStartedAt(), java.time.OffsetDateTime.now()
        ).getSeconds();

        if (verbose) {
            LOG.info(String.format(
                "ConnectionWatcher: connections=%d, idle=%ds, uptime=%ds, newActivity=%s",
                connectionCount, idleTimeSeconds, uptimeSeconds, hasNewActivity
            ));
        }

        // Decision logic:
        // 1. Server must have been running for minimum uptime (to avoid immediate shutdown)
        // 2. Must have no active connections
        // 3. Must have been idle for threshold time
        if (uptimeSeconds >= MINIMUM_UPTIME_SECONDS &&
            connectionCount == 0 &&
            idleTimeSeconds >= idleThresholdSeconds) {

            LOG.info(String.format(
                "Auto-shutdown: No connections for %d seconds (threshold: %ds)",
                idleTimeSeconds, idleThresholdSeconds
            ));

            // Send stop message to the server
            server.stop();
            stop();
        }
    }

    /**
     * Checks if the watcher is currently running.
     */
    public boolean isRunning() {
        return running;
    }
}
