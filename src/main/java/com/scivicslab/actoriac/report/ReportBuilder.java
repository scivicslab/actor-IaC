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
import java.util.Comparator;
import java.util.List;

/**
 * Assembles report sections into a unified report.
 *
 * <p>Collects {@link ReportSection} instances, sorts them by order,
 * and builds a formatted report string.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class ReportBuilder {

    private final List<ReportSection> sections = new ArrayList<>();

    /**
     * Adds a section to the report.
     *
     * @param section the section to add
     */
    public void addSection(ReportSection section) {
        if (section != null) {
            sections.add(section);
        }
    }

    /**
     * Builds the report string.
     *
     * <p>Sections are sorted by {@link ReportSection#getOrder()} and formatted as:</p>
     * <pre>
     * === Workflow Execution Report ===
     *
     * --- Section Title ---
     * Section content
     * </pre>
     *
     * <p>Sections with null or empty content are skipped.
     * Sections with null or empty title output content directly without a title line.</p>
     *
     * @return the formatted report string
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("=== Workflow Execution Report ===\n");

        // Sort sections by order and output
        sections.stream()
            .sorted(Comparator.comparingInt(ReportSection::getOrder))
            .forEach(section -> {
                String content = section.getContent();
                if (content != null && !content.isEmpty()) {
                    String title = section.getTitle();
                    if (title != null && !title.isEmpty()) {
                        sb.append("\n--- ").append(title).append(" ---\n");
                    }
                    sb.append(content).append("\n");
                }
            });

        return sb.toString();
    }
}
