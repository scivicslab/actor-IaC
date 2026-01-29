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
 * POJO section builder that outputs the workflow description.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link WorkflowDescriptionSectionIIAR} to expose as an actor.</p>
 *
 * <h2>Output example:</h2>
 * <pre>
 * [Description]
 *   Main workflow to collect Kubernetes cluster status.
 *   This workflow gathers node, namespace, and pod information.
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class WorkflowDescriptionSection implements SectionBuilder {

    private String description;

    /**
     * Sets the workflow description.
     *
     * @param description the workflow description from YAML
     */
    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String generate() {
        if (description == null || description.isEmpty()) {
            return "";  // No description, skip this section
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Description]\n");
        for (String line : description.split("\n")) {
            sb.append("  ").append(line.trim()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }

    @Override
    public int getOrder() {
        return 110;  // After workflow name
    }
}
