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

/**
 * POJO section builder that outputs the workflow name.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link WorkflowNameSectionIIAR} to expose as an actor.</p>
 *
 * <h2>Output example:</h2>
 * <pre>
 * [Workflow Name]
 * main-cluster-status
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowNameSection implements SectionBuilder {

    private String workflowName;
    private String workflowPath;

    /**
     * Sets the workflow name.
     *
     * @param name the workflow name from YAML
     */
    public void setWorkflowName(String name) {
        this.workflowName = name;
    }

    /**
     * Sets the workflow file path (used as fallback if name is not set).
     *
     * @param path the workflow file path
     */
    public void setWorkflowPath(String path) {
        this.workflowPath = path;
    }

    @Override
    public String generate() {
        String name = workflowName;
        if (name == null || name.isEmpty()) {
            // Fall back to file name
            if (workflowPath != null && !workflowPath.isEmpty()) {
                int lastSlash = workflowPath.lastIndexOf('/');
                name = lastSlash >= 0 ? workflowPath.substring(lastSlash + 1) : workflowPath;
            } else {
                name = "(unknown)";
            }
        }
        return "[Workflow Name]\n" + name + "\n";
    }

    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }

    @Override
    public int getOrder() {
        return 100;  // Display first
    }
}
