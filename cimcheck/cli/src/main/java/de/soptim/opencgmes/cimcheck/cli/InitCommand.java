/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimcheck.cli;

import de.soptim.opencgmes.cimcheck.core.ConfigTemplate;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The {@code cimcheck init} subcommand: scaffolds a commented {@code opencgmes.json} so users do
 * not have to author config by hand. Point its {@code schemas}/{@code schemasDirectory} at the
 * profiles to validate against; the generated file is mostly commented out and serves as
 * documentation.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — file written</li>
 *   <li>2 — the file already exists (use {@code --force}) or could not be written</li>
 * </ul>
 */
@Command(
        name        = "init",
        description = {
            "Create a commented opencgmes.json config scaffold in the current directory.",
            "Set 'schemas' to point CIMcheck at the profiles to validate against."
        },
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public class InitCommand implements Callable<Integer> {

    @Option(
            names       = {"-d", "--dir"},
            paramLabel  = "<dir>",
            description = "Directory to write opencgmes.json into (default: current directory)."
    )
    private Path dir = Path.of(".");

    @Option(
            names       = {"-f", "--force"},
            description = "Overwrite an existing opencgmes.json."
    )
    private boolean force;

    @Override
    public Integer call() {
        Path target = dir.resolve(ConfigTemplate.FILE_NAME);
        if (Files.exists(target) && !force) {
            System.err.println("Error: " + target + " already exists. Use --force to overwrite.");
            return ExitCode.USAGE;
        }
        try {
            Files.writeString(target, ConfigTemplate.defaultJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Error: cannot write " + target + ": " + e.getMessage());
            return ExitCode.SOFTWARE;
        }
        System.out.println("Created " + target);
        System.out.println("Edit \"schemas\"/\"schemasDirectory\" to point CIMcheck at your "
                + "CGMES profiles (without them, validation is syntax-only).");
        return ExitCode.OK;
    }
}
