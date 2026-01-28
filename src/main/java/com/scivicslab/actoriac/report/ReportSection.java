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
 * Interface for report sections.
 *
 * <p>Each implementation provides a specific type of content for the final report.
 * Sections are ordered by their {@link #getOrder()} value when assembled.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public interface ReportSection {

    /**
     * Returns the section title.
     *
     * <p>If null or empty, the section content is output without a title line.</p>
     *
     * @return the section title, or null for no title
     */
    String getTitle();

    /**
     * Returns the section content.
     *
     * <p>If null or empty, the section is skipped in the report.</p>
     *
     * @return the section content
     */
    String getContent();

    /**
     * Returns the display order.
     *
     * <p>Sections with lower order values are displayed first.
     * Recommended values:</p>
     * <ul>
     *   <li>100 - Workflow info (header area)</li>
     *   <li>200 - Check results (% messages)</li>
     *   <li>300 - Transitions</li>
     *   <li>400 - Collected data (JsonState)</li>
     * </ul>
     *
     * @return the order value
     */
    int getOrder();
}
