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

package de.soptim.opencgmes.cimxmlcli.progress;

import de.soptim.opencgmes.cimxmlcli.model.ParsedModel;
import de.soptim.opencgmes.cimxmlcli.schema.LoadedSchema;
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Prints progress as plain counter lines to stderr, following <a
 * href="https://clig.dev">clig.dev</a>.
 */
public final class ConsoleProgressReporter implements ProgressListener {

  private final PrintWriter err;
  private final Verbosity verbosity;

  /** Creates a reporter printing to {@code err}. */
  public ConsoleProgressReporter(PrintWriter err, Verbosity verbosity) {
    this.err = err;
    this.verbosity = verbosity;
  }

  @Override
  public void schemaReadingStarted(Path folder) {
    print("Loading RDFS profiles from " + folder);
  }

  @Override
  public void schemaLoaded(int index, LoadedSchema schema) {
    final var profile = schema.profile();
    final var keyword = profile.isHeaderProfile() ? "FH" : profile.getDcatKeyword();
    print(String.format(Locale.ROOT, "  [%2d] %-6s <- %s", index, keyword, schema.displayPath()));
  }

  @Override
  public void warning(Problem warning) {
    print("warning: " + warning.format().replace("\n", "\n  "));
  }

  @Override
  public void modelReadingStarted(Path folder) {
    print("Parsing CIMXML models from " + folder);
  }

  @Override
  public void modelParsed(int index, ParsedModel model, Duration time) {
    final var keywords = model.keywords();
    final var keyword = keywords.isEmpty() ? "?" : String.join("+", keywords);
    print(
        String.format(
            Locale.ROOT,
            "  [%2d] %-6s <- %s (%s%s)",
            index,
            keyword,
            model.displayPath(),
            seconds(time),
            warningSummary(model)));
    if (verbosity == Verbosity.VERBOSE) {
      model.warnings().messages().forEach(w -> print("         warning: " + w));
    }
  }

  @Override
  public void writingStarted(Path target) {
    print("Writing " + target + " ...");
  }

  @Override
  public void writingFinished(Path target, long quads, long bytes, Duration time) {
    print(
        String.format(
            Locale.ROOT,
            "Done: %s (%s, %,d quads, %s)",
            target,
            seconds(time),
            quads,
            megabytes(bytes)));
  }

  private void print(String line) {
    if (verbosity != Verbosity.QUIET) {
      err.println(line);
      err.flush();
    }
  }

  private static String warningSummary(ParsedModel model) {
    final var warnings = model.warnings();
    if (warnings.total() == 0) {
      return "";
    }
    // Name only the most frequent kind to keep the line short
    final var top = warnings.countsByKind().keySet().iterator().next();
    final var others = warnings.countsByKind().size() - 1;
    return ", "
        + warnings.total()
        + " warnings: \""
        + top
        + "\""
        + (others > 0 ? " and " + others + " more kind(s), see -v" : "");
  }

  private static String seconds(Duration time) {
    return String.format(Locale.ROOT, "%.1f s", time.toMillis() / 1000.0);
  }

  private static String megabytes(long bytes) {
    return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
  }
}
