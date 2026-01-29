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
 * POJO section builder that outputs an actor's JsonState in YAML format.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link JsonStateSectionIIAR} to expose as an actor.</p>
 *
 * <p>Any IIActorRef can hold JsonState data. This section builder
 * retrieves that data and outputs it in human-readable YAML format.</p>
 *
 * <h2>Output example:</h2>
 * <pre>
 * [JsonState: nodeGroup]
 * cluster:
 *   name: production
 *   nodes:
 *     - hostname: server1
 *       cpu: 8
 *       memory: 32Gi
 *     - hostname: server2
 *       cpu: 16
 *       memory: 64Gi
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class JsonStateSection implements SectionBuilder {

    private String actorName;
    private String yamlContent;
    private String jsonPath;

    /**
     * Sets the actor name (for display in title).
     *
     * @param actorName the actor name
     */
    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    /**
     * Sets the YAML content to output.
     *
     * @param yamlContent the YAML-formatted JsonState content
     */
    public void setYamlContent(String yamlContent) {
        this.yamlContent = yamlContent;
    }

    /**
     * Sets the JSON path filter (optional).
     *
     * <p>If set, only the specified path within the JsonState is output.
     * For example, "cluster.nodes" would output only the nodes array.</p>
     *
     * @param jsonPath the JSON path filter, or null for entire state
     */
    public void setJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
    }

    @Override
    public String generate() {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return "";  // No content, skip this section
        }

        StringBuilder sb = new StringBuilder();
        String title = "[JsonState: " + (actorName != null ? actorName : "unknown") + "]";
        if (jsonPath != null && !jsonPath.isEmpty()) {
            title += " (path: " + jsonPath + ")";
        }
        sb.append(title).append("\n");
        sb.append(yamlContent);
        if (!yamlContent.endsWith("\n")) {
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }
}
