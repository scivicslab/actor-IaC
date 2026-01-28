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

package com.scivicslab.actoriac;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Provides version information for actor-IaC.
 *
 * <p>The version is read from version.properties which is populated
 * by Maven resource filtering at build time.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 */
public final class Version {

    private static final String VERSION;

    static {
        String version = "unknown";
        try (InputStream is = Version.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                version = props.getProperty("version", "unknown");
            }
        } catch (IOException e) {
            // Ignore, use default
        }
        VERSION = version;
    }

    private Version() {
        // Utility class
    }

    /**
     * Returns the actor-IaC version.
     *
     * @return the version string (e.g., "2.14.0-SNAPSHOT")
     */
    public static String get() {
        return VERSION;
    }

    /**
     * Returns the full version string for CLI display.
     *
     * @return formatted version string (e.g., "actor-IaC 2.14.0-SNAPSHOT")
     */
    public static String full() {
        return "actor-IaC " + VERSION;
    }

    /**
     * Returns the version string for a specific command.
     *
     * @param command the command name (e.g., "run", "list")
     * @return formatted version string (e.g., "actor-IaC run 2.14.0-SNAPSHOT")
     */
    public static String forCommand(String command) {
        return "actor-IaC " + command + " " + VERSION;
    }
}
