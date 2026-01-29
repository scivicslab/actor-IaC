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

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import com.scivicslab.pojoactor.core.JsonState;
import com.scivicslab.pojoactor.workflow.ActorSystemAware;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Section-based workflow report builder.
 *
 * <p>Assembles {@link ReportSection} instances into a unified report.
 * Can be used from workflows via {@code loader.createChild}.</p>
 *
 * <h2>Usage in workflows:</h2>
 * <pre>{@code
 * steps:
 *   - states: ["0", "1"]
 *     actions:
 *       - actor: loader
 *         method: createChild
 *         arguments: ["ROOT", "reportBuilder", "com.scivicslab.actoriac.report.ReportBuilder"]
 *       - actor: reportBuilder
 *         method: addWorkflowInfo
 *
 *   - states: ["1", "2"]
 *     actions:
 *       - actor: nodeGroup
 *         method: apply
 *         arguments:
 *           actor: "node-*"
 *           method: runWorkflow
 *           arguments: ["sub-workflow.yaml"]
 *
 *   - states: ["2", "end"]
 *     actions:
 *       - actor: reportBuilder
 *         method: addJsonStateSection
 *         arguments:
 *           actor: "node-localhost"
 *       - actor: reportBuilder
 *         method: report
 * }</pre>
 *
 * <h2>Actions:</h2>
 * <ul>
 *   <li>{@code addWorkflowInfo} - Add workflow metadata section</li>
 *   <li>{@code addJsonStateSection} - Add actor's JsonState as YAML (args: {"actor": "name", "path": "optional"})</li>
 *   <li>{@code report} - Build and output the report to outputMultiplexer</li>
 * </ul>
 *
 * <p>Transition履歴の詳細表示は{@code TransitionViewerPlugin}で行う。</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class ReportBuilder implements CallableByActionName, ActorSystemAware {

    private static final String CLASS_NAME = ReportBuilder.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    private final List<ReportSection> sections = new ArrayList<>();
    private IIActorSystem system;

    /**
     * Default constructor for use with loader.createChild.
     */
    public ReportBuilder() {
    }

    @Override
    public void setActorSystem(IIActorSystem system) {
        this.system = system;
        logger.info("ReportBuilder: ActorSystem set");
    }

    // ========================================================================
    // CallableByActionName implementation
    // ========================================================================

    @Override
    public ActionResult callByActionName(String actionName, String args) {
        logger.info("ReportBuilder.callByActionName: " + actionName);

        return switch (actionName) {
            case "addWorkflowInfo" -> addWorkflowInfo(args);
            case "addJsonStateSection" -> addJsonStateSection(args);
            case "report" -> report(args);
            default -> new ActionResult(false, "Unknown action: " + actionName);
        };
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Adds workflow info section.
     */
    private ActionResult addWorkflowInfo(String args) {
        String workflowPath = getWorkflowPathFromNodeGroup();
        if (workflowPath == null) {
            return new ActionResult(false, "Could not get workflow path from nodeGroup");
        }

        String name = null;
        String description = null;

        // Try to read workflow YAML for name and description
        try {
            Path path = Paths.get(workflowPath);
            if (!Files.exists(path)) {
                path = Paths.get(System.getProperty("user.dir"), workflowPath);
            }

            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    Yaml yaml = new Yaml();
                    Map<String, Object> data = yaml.load(is);
                    if (data != null) {
                        name = (String) data.get("name");
                        Object descObj = data.get("description");
                        description = descObj != null ? descObj.toString().trim() : null;
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("ReportBuilder.addWorkflowInfo: Could not read workflow file: " + e.getMessage());
        }

        addSection(new WorkflowInfoSection(workflowPath, name, description));
        return new ActionResult(true, "Workflow info section added");
    }

    /**
     * Adds JsonState section for specified actor.
     */
    private ActionResult addJsonStateSection(String args) {
        String actorName;
        String path = "";

        try {
            JSONObject json = new JSONObject(args);
            actorName = json.getString("actor");
            path = json.optString("path", "");
        } catch (Exception e) {
            return new ActionResult(false, "Invalid arguments: " + e.getMessage() +
                ". Expected: {\"actor\": \"name\", \"path\": \"optional\"}");
        }

        if (system == null) {
            return new ActionResult(false, "ActorSystem not available");
        }

        IIActorRef<?> targetActor = system.getIIActor(actorName);
        if (targetActor == null) {
            return new ActionResult(false, "Actor not found: " + actorName);
        }

        JsonState jsonState = targetActor.json();
        if (jsonState == null) {
            return new ActionResult(false, "Actor has no JsonState: " + actorName);
        }

        String yamlContent = jsonState.toStringOfYaml(path);
        addSection(new JsonStateSection(actorName, yamlContent));

        return new ActionResult(true, "JsonState section added for " + actorName);
    }

    /**
     * Builds and outputs the report.
     */
    private ActionResult report(String args) {
        String reportContent = build();
        reportToMultiplexer(reportContent);
        return new ActionResult(true, reportContent);
    }

    // ========================================================================
    // Core Methods
    // ========================================================================

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

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private String getWorkflowPathFromNodeGroup() {
        if (system == null) return null;

        IIActorRef<?> nodeGroup = system.getIIActor("nodeGroup");
        if (nodeGroup == null) return null;

        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    private void reportToMultiplexer(String data) {
        if (system == null) return;

        IIActorRef<?> multiplexer = system.getIIActor("outputMultiplexer");
        if (multiplexer == null) return;

        JSONObject arg = new JSONObject();
        arg.put("source", "report-builder");
        arg.put("type", "plugin-result");
        arg.put("data", data);
        multiplexer.callByActionName("add", arg.toString());
    }
}
