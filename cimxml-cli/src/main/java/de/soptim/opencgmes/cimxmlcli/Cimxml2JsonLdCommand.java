/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimxmlcli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The {@code cimxml2jsonld} command: converts the CIMXML files of one CGMES IGM, together with the
 * RDFS profiles they reference, into a single JSON-LD dataset that can be queried with SPARQL.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>0: the dataset was written
 *   <li>1: the conversion failed (invalid input, I/O error, ...)
 *   <li>2: usage error (missing or invalid options)
 * </ul>
 */
@Command(
    name = "cimxml2jsonld",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    sortOptions = false,
    usageHelpAutoWidth = true,
    description =
        "Converts the CIMXML files of one CGMES IGM (independent grid model) into a queryable"
            + " JSON-LD dataset with the named graphs cgmes:schema:<keyword>,"
            + " cgmes:header:<keyword> and cgmes:model:<keyword>.%n",
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {"0:the dataset was written", "1:the conversion failed", "2:usage error"})
public final class Cimxml2JsonLdCommand implements Callable<Integer> {

  @Spec private CommandSpec spec;

  @Option(
      names = {"-s", "--schema"},
      required = true,
      paramLabel = "<dir>",
      description = "Folder with the CGMES RDFS files (searched recursively, ZIPs included).")
  private Path schemaFolder;

  @Option(
      names = {"-m", "--model"},
      required = true,
      paramLabel = "<dir>",
      description =
          "Folder with the CIMXML files of one IGM (searched recursively, ZIPs included).")
  private Path modelFolder;

  @Option(
      names = {"-o", "--output"},
      required = true,
      paramLabel = "<file>",
      description = "Output file, must end with .jsonld or .jsonld.gz (gzip-compressed).")
  private Path outputFile;

  @Option(
      names = {"-c", "--compact"},
      description =
          "Write JSON-LD in compacted form (with @context). Default: expanded form. Needs"
              + " considerably more memory.")
  private boolean compact;

  @Option(
      names = {"-q", "--quiet"},
      description = "Only print errors.")
  private boolean quiet;

  @Option(
      names = {"-v", "--verbose"},
      description = "Also print debug output, individual warnings and stack traces.")
  private boolean verbose;

  @Override
  public Integer call() {
    validateOptions();
    spec.commandLine().getErr().println("cimxml2jsonld: the conversion is not implemented yet.");
    return ExitCode.SOFTWARE;
  }

  private void validateOptions() {
    if (quiet && verbose) {
      throw usageError("--quiet and --verbose cannot be combined.");
    }
    requireDirectory(schemaFolder, "--schema");
    requireDirectory(modelFolder, "--model");
  }

  private void requireDirectory(Path folder, String option) {
    if (!Files.isDirectory(folder)) {
      throw usageError(option + ": '" + folder + "' is not an existing directory.");
    }
  }

  private ParameterException usageError(String message) {
    return new ParameterException(spec.commandLine(), message);
  }
}
