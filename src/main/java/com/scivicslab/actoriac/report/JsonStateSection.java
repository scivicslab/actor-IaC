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
 * Report section for actor JsonState data in YAML format.
 *
 * <p>Displays collected data from an actor's JsonState as YAML.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class JsonStateSection implements ReportSection {

    private final String actorName;
    private final String yamlContent;

    /**
     * Creates a JsonState section.
     *
     * @param actorName the name of the actor whose data is displayed
     * @param yamlContent the YAML-formatted content from JsonState
     */
    public JsonStateSection(String actorName, String yamlContent) {
        this.actorName = actorName;
        this.yamlContent = yamlContent;
    }

    @Override
    public String getTitle() {
        return "Collected Data (" + actorName + ")";
    }

    @Override
    public String getContent() {
        return yamlContent;
    }
}
