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

/**
 * Report section for workflow metadata.
 *
 * <p>Displays workflow file path, name, and optional description.
 * Returns null title to output content directly without a title line.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class WorkflowInfoSection implements ReportSection {

    private final String workflowPath;
    private final String name;
    private final String description;

    /**
     * Creates a workflow info section.
     *
     * @param workflowPath the workflow file path
     * @param name the workflow name
     * @param description the workflow description (may be null)
     */
    public WorkflowInfoSection(String workflowPath, String name, String description) {
        this.workflowPath = workflowPath;
        this.name = name;
        this.description = description;
    }

    @Override
    public String getTitle() {
        return null;  // No title - content is output directly
    }

    @Override
    public String getContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Workflow Info]\n");
        sb.append("  File: ").append(workflowPath).append("\n");
        if (name != null && !name.isEmpty()) {
            sb.append("  Name: ").append(name).append("\n");
        }
        if (description != null && !description.isEmpty()) {
            sb.append("\n[Description]\n");
            for (String line : description.split("\n")) {
                sb.append("  ").append(line.trim()).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public int getOrder() {
        return 100;  // Display first (header area)
    }
}
