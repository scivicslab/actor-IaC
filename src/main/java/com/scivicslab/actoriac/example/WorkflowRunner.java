/*
 * Copyright 2025 devteam@scivics-lab.com
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

package com.scivicslab.actoriac.example;

import java.io.FileInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.actoriac.NodeGroup;
import com.scivicslab.actoriac.NodeGroupIIAR;
import com.scivicslab.pojoactor.ActionResult;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * Generic workflow runner for actor-IaC (Level 3).
 *
 * <p>This class provides a reusable entry point for executing workflows
 * on infrastructure nodes. The actual operations are defined in external
 * YAML/JSON/XML workflow files, not in Java code.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar actor-IaC.jar inventory.ini webservers workflow.yaml
 * </pre>
 *
 * <h2>Actor Hierarchy</h2>
 * <pre>
 * IIActorSystem
 *   └─ NodeGroupIIAR (parent)
 *        ├─ NodeIIAR (child, autonomous agent)
 *        ├─ NodeIIAR (child, autonomous agent)
 *        └─ NodeIIAR (child, autonomous agent)
 * </pre>
 *
 * <h2>Design Principle</h2>
 * <p>This Java code is generic and does not need modification when
 * changing targets or operations. All customization is done through:</p>
 * <ul>
 *   <li><strong>inventory.ini</strong> - Define hosts and groups</li>
 *   <li><strong>workflow.yaml</strong> - Define operations to execute</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 */
public class WorkflowRunner {

    private static final Logger LOG = Logger.getLogger(WorkflowRunner.class.getName());

    private final IIActorSystem system;
    private final NodeGroupIIAR nodeGroupActor;

    /**
     * Constructs a WorkflowRunner with the specified system and parent actor.
     *
     * @param system the IIActorSystem for workflow execution
     * @param nodeGroupActor the NodeGroupIIAR parent actor
     */
    public WorkflowRunner(IIActorSystem system, NodeGroupIIAR nodeGroupActor) {
        this.system = system;
        this.nodeGroupActor = nodeGroupActor;
    }

    /**
     * Main entry point for workflow execution.
     *
     * <p>Arguments:</p>
     * <ol>
     *   <li>inventory - Path to Ansible inventory file</li>
     *   <li>group - Name of the host group to target</li>
     *   <li>workflow - Path to workflow file (YAML/JSON/XML)</li>
     * </ol>
     *
     * @param args command line arguments
     * @throws Exception if execution fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -jar actor-IaC.jar <inventory> <group> <workflow>");
            System.out.println();
            System.out.println("Arguments:");
            System.out.println("  inventory  - Path to Ansible inventory file (e.g., inventory.ini)");
            System.out.println("  group      - Name of the host group to target (e.g., webservers)");
            System.out.println("  workflow   - Path to workflow file (e.g., deploy.yaml)");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  java -jar actor-IaC.jar inventory.ini webservers deploy.yaml");
            return;
        }

        String inventoryPath = args[0];
        String groupName = args[1];
        String workflowPath = args[2];

        LOG.info("=== actor-IaC Workflow Runner ===");
        LOG.log(Level.INFO, "Inventory: {0}", inventoryPath);
        LOG.log(Level.INFO, "Group: {0}", groupName);
        LOG.log(Level.INFO, "Workflow: {0}", workflowPath);

        IIActorSystem system = new IIActorSystem("actor-iac-workflow");

        try {
            // Step 1: Create NodeGroup POJO (Level 1 functionality)
            // Pure POJO creation, independent of IIActorSystem
            NodeGroup nodeGroup = new NodeGroup.Builder()
                .withInventory(new FileInputStream(inventoryPath))
                .build();
            LOG.info("NodeGroup POJO created");

            // Step 2: Convert POJO to IIActor (Level 3 functionality)
            // NodeGroupIIAR wraps NodeGroup with workflow capabilities
            NodeGroupIIAR nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroup, system);
            system.addIIActor(nodeGroupActor);
            LOG.info("NodeGroupIIAR actor created");

            // Run the workflow
            WorkflowRunner runner = new WorkflowRunner(system, nodeGroupActor);
            runner.run(groupName, workflowPath);

            LOG.info("=== Complete ===");
        } finally {
            system.terminate();
        }
    }

    /**
     * Executes the workflow on the specified group.
     *
     * @param groupName the name of the host group
     * @param workflowPath the path to the workflow file
     * @throws Exception if execution fails
     */
    public void run(String groupName, String workflowPath) throws Exception {
        // Step 1: Create child NodeIIARs for the group
        LOG.log(Level.INFO, "Creating node actors for group: {0}", groupName);
        ActionResult createResult = nodeGroupActor.callByActionName(
            "createNodeActors", "[\"" + groupName + "\"]");

        if (!createResult.isSuccess()) {
            LOG.log(Level.SEVERE, "Failed to create node actors: {0}", createResult.getResult());
            return;
        }
        LOG.log(Level.INFO, "Create result: {0}", createResult.getResult());

        // Step 2: Apply workflow to all nodes
        LOG.log(Level.INFO, "Applying workflow: {0}", workflowPath);
        ActionResult result = nodeGroupActor.callByActionName(
            "applyWorkflowToAllNodes", "[\"" + workflowPath + "\"]");

        LOG.info("Workflow execution result:");
        LOG.log(Level.INFO, "  Success: {0}", result.isSuccess());
        LOG.log(Level.INFO, "  Message: {0}", result.getResult());
    }

    /**
     * Executes a single command on all nodes in the group.
     *
     * <p>This is a convenience method for simple use cases where
     * a full workflow is not needed.</p>
     *
     * @param groupName the name of the host group
     * @param command the command to execute
     * @throws Exception if execution fails
     */
    public void executeCommand(String groupName, String command) throws Exception {
        // Create child NodeIIARs if not already created
        ActionResult createResult = nodeGroupActor.callByActionName(
            "createNodeActors", "[\"" + groupName + "\"]");

        if (!createResult.isSuccess()) {
            LOG.log(Level.SEVERE, "Failed to create node actors: {0}", createResult.getResult());
            return;
        }

        // Execute command on all nodes
        LOG.log(Level.INFO, "Executing command on all nodes: {0}", command);
        ActionResult result = nodeGroupActor.callByActionName(
            "executeCommandOnAllNodes", "[\"" + command + "\"]");

        LOG.log(Level.INFO, "Command result: {0}", result.getResult());
    }
}
