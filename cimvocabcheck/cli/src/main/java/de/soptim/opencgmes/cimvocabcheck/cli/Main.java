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

package de.soptim.opencgmes.cimvocabcheck.cli;

import picocli.CommandLine;
import picocli.CommandLine.ExitCode;

/** Entry point for the {@code cimvocabcheck} command-line tool. */
public final class Main {

  private Main() {}

  /** Runs the CLI with {@code args} and exits with the resulting status code. */
  public static void main(String[] args) {
    int exitCode = commandLine().execute(args);
    System.exit(exitCode);
  }

  /**
   * The CLI as {@link #main} runs it.
   *
   * <p>picocli exits with 1 when a command throws, which the published report contract reserves for
   * "the report has errors". A crash produced no report at all, so it exits with 2 — the code
   * consumers already treat as "the tool failed, stdout holds no report".
   */
  static CommandLine commandLine() {
    return new CommandLine(new ValidateCommand())
        .setExecutionExceptionHandler(
            (error, commandLine, parseResult) -> {
              commandLine.getErr().println("Error: internal failure: " + error);
              error.printStackTrace(commandLine.getErr());
              return ExitCode.USAGE;
            });
  }
}
