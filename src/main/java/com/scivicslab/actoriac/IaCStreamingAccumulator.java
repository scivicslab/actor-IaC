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

package com.scivicslab.actoriac;

import com.github.ricksbrown.cowsay.Cowsay;
import com.scivicslab.pojoactor.core.accumulator.StreamingAccumulator;

/**
 * StreamingAccumulator with cowsay display support for actor-IaC.
 *
 * <p>This accumulator extends {@link StreamingAccumulator} to add cowsay
 * ASCII art visualization for workflow step transitions. The cowfile (character)
 * can be customized to show different ASCII art animals.</p>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * IaCStreamingAccumulator acc = new IaCStreamingAccumulator();
 * acc.setCowfile("tux");  // Use Linux penguin
 * acc.cowsay("my-workflow", "- states: [\"0\", \"1\"]\n  actions: ...");
 * }</pre>
 *
 * <h2>Available Cowfiles</h2>
 * <p>44 cowfiles are available including: tux, dragon, stegosaurus, kitty,
 * bunny, turtle, elephant, ghostbusters, vader, and many more.</p>
 *
 * @author devteam@scivics-lab.com
 * @since 2.12.0
 */
public class IaCStreamingAccumulator extends StreamingAccumulator {

    /**
     * Cowfile name for cowsay output.
     * When null, uses the default cow.
     */
    private String cowfile = null;

    /**
     * Constructs an IaCStreamingAccumulator with default settings.
     */
    public IaCStreamingAccumulator() {
        super();
    }

    /**
     * Sets the cowfile name for cowsay output.
     *
     * <p>Available cowfiles include: tux, dragon, stegosaurus, kitty, bunny,
     * turtle, elephant, ghostbusters, vader, and many more (44 total).</p>
     *
     * @param cowfile the cowfile name (e.g., "tux", "dragon"), or null for default cow
     */
    public void setCowfile(String cowfile) {
        this.cowfile = cowfile;
    }

    /**
     * Gets the cowfile name for cowsay output.
     *
     * @return the cowfile name, or null if using default cow
     */
    public String getCowfile() {
        return cowfile;
    }

    /**
     * Renders a workflow step as cowsay ASCII art and returns the result.
     *
     * <p>This method formats the workflow name and step YAML into a cowsay
     * message with the configured cowfile character. The rendered ASCII art
     * is returned as a string instead of being printed directly, allowing
     * the caller to send it to the output multiplexer.</p>
     *
     * @param workflowName the name of the workflow
     * @param stepYaml the YAML representation of the step (first 10 lines recommended)
     * @return the rendered cowsay ASCII art string
     */
    public String renderCowsay(String workflowName, String stepYaml) {
        String displayText = "[" + workflowName + "]\n" + stepYaml;
        String[] cowsayArgs;
        if (cowfile != null && !cowfile.isBlank()) {
            cowsayArgs = new String[] { "-f", cowfile, displayText };
        } else {
            cowsayArgs = new String[] { displayText };
        }
        return Cowsay.say(cowsayArgs);
    }

    /**
     * Lists all available cowfile names.
     *
     * @return array of available cowfile names
     */
    public static String[] listCowfiles() {
        String[] listArgs = { "-l" };
        String cowList = Cowsay.say(listArgs);
        return cowList.trim().split("\\s+");
    }
}
