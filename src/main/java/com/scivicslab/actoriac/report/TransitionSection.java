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

package com.scivicslab.actoriac.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Report section for state transition summary.
 *
 * <p>Displays a list of transitions with success/failure status
 * and a summary count.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class TransitionSection implements ReportSection {

    private final List<TransitionEntry> transitions = new ArrayList<>();

    /**
     * Adds a transition entry.
     *
     * @param transition the transition description (e.g., "0 -> 1")
     * @param success true if the transition succeeded
     * @param errorMessage the error message if failed (may be null)
     */
    public void addTransition(String transition, boolean success, String errorMessage) {
        transitions.add(new TransitionEntry(transition, success, errorMessage));
    }

    @Override
    public String getTitle() {
        return "Transitions";
    }

    @Override
    public String getContent() {
        if (transitions.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int succeeded = 0;
        int failed = 0;

        for (TransitionEntry entry : transitions) {
            if (entry.success) {
                sb.append("[✓] ").append(entry.transition).append("\n");
                succeeded++;
            } else {
                sb.append("[✗] ").append(entry.transition);
                if (entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                    sb.append(": ").append(entry.errorMessage);
                }
                sb.append("\n");
                failed++;
            }
        }

        sb.append("\nSummary: ").append(succeeded).append(" succeeded, ")
          .append(failed).append(" failed");

        return sb.toString();
    }

    @Override
    public int getOrder() {
        return 300;
    }

    /**
     * Internal record for transition entries.
     */
    private static class TransitionEntry {
        final String transition;
        final boolean success;
        final String errorMessage;

        TransitionEntry(String transition, boolean success, String errorMessage) {
            this.transition = transition;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
