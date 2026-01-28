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

package com.scivicslab.actoriac.cli;

import com.scivicslab.actoriac.Version;
import picocli.CommandLine.IVersionProvider;

/**
 * Provides version information for picocli commands.
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] { Version.full() };
    }
}
