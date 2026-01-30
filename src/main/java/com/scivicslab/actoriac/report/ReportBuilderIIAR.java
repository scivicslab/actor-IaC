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

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.JsonState;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Actor for building and outputting workflow reports.
 *
 * <p>This actor wraps a {@link ReportBuilder} POJO and provides workflow-callable
 * actions via {@code @Action} annotations. It handles the integration with the
 * actor system, database, and output multiplexer.</p>
 *
 * <h2>Actions:</h2>
 * <ul>
 *   <li>{@code addWorkflowInfo} - Add workflow metadata section</li>
 *   <li>{@code addJsonStateSection} - Add actor's JsonState as YAML</li>
 *   <li>{@code report} - Build and output the report</li>
 * </ul>
 *
 * <p>Transition履歴の詳細表示は{@code TransitionViewerPlugin}で行う。</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
public class ReportBuilderIIAR extends IIActorRef<ReportBuilder> {

    private static final String CLASS_NAME = ReportBuilderIIAR.class.getName();
    private static final Logger logger = Logger.getLogger(CLASS_NAME);

    /**
     * Constructs a new ReportBuilderIIAR with a new POJO instance.
     *
     * <p>Required by {@code loader.createChild} for dynamic instantiation.</p>
     *
     * @param actorName the actor name
     * @param system the actor system
     */
    public ReportBuilderIIAR(String actorName, IIActorSystem system) {
        super(actorName, new ReportBuilder(), system);
        // POJOにsystemとselfRefを設定（ReportBuilder.build()が子アクターを取得するため）
        this.object.setActorSystem(system);
        this.object.setIIActorRef(this);
    }

    /**
     * Constructs a new ReportBuilderIIAR.
     *
     * @param name the actor name
     * @param builder the ReportBuilder POJO to wrap
     */
    public ReportBuilderIIAR(String name, ReportBuilder builder) {
        super(name, builder);
    }

    /**
     * Constructs a new ReportBuilderIIAR with actor system.
     *
     * @param name the actor name
     * @param builder the ReportBuilder POJO to wrap
     * @param system the actor system
     */
    public ReportBuilderIIAR(String name, ReportBuilder builder, IIActorSystem system) {
        super(name, builder, system);
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Adds workflow info section.
     *
     * <p>Gets workflow path from nodeGroup, reads the YAML file to extract
     * name and description, and adds a WorkflowInfoSection to the report.</p>
     *
     * @param args unused
     * @return ActionResult indicating success or failure
     */
    @Action("addWorkflowInfo")
    public ActionResult addWorkflowInfo(String args) {
        logger.info("ReportBuilderIIAR.addWorkflowInfo");

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
            logger.warning("ReportBuilderIIAR.addWorkflowInfo: Could not read workflow file: " + e.getMessage());
        }

        this.object.addSection(new WorkflowInfoSection(workflowPath, name, description));
        return new ActionResult(true, "Workflow info section added");
    }

    /**
     * Adds JsonState section for specified actor.
     *
     * <p>Arguments (JSON):</p>
     * <ul>
     *   <li>{@code actor} - Actor name (required)</li>
     *   <li>{@code path} - JsonState path filter (optional)</li>
     * </ul>
     *
     * @param args JSON arguments
     * @return ActionResult indicating success or failure
     */
    @Action("addJsonStateSection")
    public ActionResult addJsonStateSection(String args) {
        logger.info("ReportBuilderIIAR.addJsonStateSection: args=" + args);

        String actorName;
        String path = "";

        try {
            JSONObject json = new JSONObject(args);
            actorName = json.getString("actor");
            path = json.optString("path", "");
        } catch (Exception e) {
            return new ActionResult(false, "Invalid arguments: " + e.getMessage());
        }

        if (system() == null) {
            return new ActionResult(false, "ActorSystem not available");
        }

        IIActorRef<?> targetActor = ((IIActorSystem) system()).getIIActor(actorName);
        if (targetActor == null) {
            return new ActionResult(false, "Actor not found: " + actorName);
        }

        JsonState jsonState = targetActor.json();
        if (jsonState == null) {
            return new ActionResult(false, "Actor has no JsonState: " + actorName);
        }

        String yamlContent = jsonState.toStringOfYaml(path);
        this.object.addSection(new JsonStateSection(actorName, yamlContent));

        return new ActionResult(true, "JsonState section added for " + actorName);
    }

    /**
     * Builds and outputs the report.
     *
     * @param args unused
     * @return ActionResult with the report content
     */
    @Action("report")
    public ActionResult report(String args) {
        logger.info("ReportBuilderIIAR.report");

        String reportContent = this.object.build();
        reportToMultiplexer(reportContent);

        return new ActionResult(true, reportContent);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Gets workflow path from nodeGroup actor.
     */
    private String getWorkflowPathFromNodeGroup() {
        if (system() == null) return null;
        IIActorSystem iiSystem = (IIActorSystem) system();

        IIActorRef<?> nodeGroup = iiSystem.getIIActor("nodeGroup");
        if (nodeGroup == null) return null;

        ActionResult result = nodeGroup.callByActionName("getWorkflowPath", "");
        return result.isSuccess() ? result.getResult() : null;
    }

    /**
     * Outputs report to outputMultiplexer.
     */
    private void reportToMultiplexer(String data) {
        if (system() == null) return;
        IIActorSystem iiSystem = (IIActorSystem) system();

        IIActorRef<?> multiplexer = iiSystem.getIIActor("outputMultiplexer");
        if (multiplexer == null) return;

        JSONObject arg = new JSONObject();
        arg.put("source", "report-builder");
        arg.put("type", "plugin-result");
        arg.put("data", data);
        multiplexer.callByActionName("add", arg.toString());
    }
}
