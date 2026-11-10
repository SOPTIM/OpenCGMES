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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimxmlcli.TestData;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConsoleProgressReporterTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final StringWriter text = new StringWriter();

  private ConsoleProgressReporter reporter(Verbosity verbosity) {
    return new ConsoleProgressReporter(new PrintWriter(text), verbosity);
  }

  @Test
  public void printsCounterLines() throws IOException {
    final var context =
        TestData.miniContext(tmp.newFolder("rdfs").toPath(), tmp.newFolder("models").toPath());
    final var reporter = reporter(Verbosity.NORMAL);

    reporter.modelParsed(3, context.models().getFirst(), Duration.ofMillis(1250));
    reporter.writingFinished(Path.of("out.jsonld"), 12345, 3 * 1024 * 1024, Duration.ofSeconds(2));

    assertEquals(
        String.format(
            "  [ 3] EQ     <- MINI_EQ.xml (1.3 s)%nDone: out.jsonld (2.0 s, 12,345 quads, 3.0"
                + " MB)%n"),
        text.toString());
  }

  @Test
  public void quietPrintsNothing() {
    final var reporter = reporter(Verbosity.QUIET);

    reporter.schemaReadingStarted(Path.of("rdfs"));
    reporter.writingStarted(Path.of("out.jsonld"));

    assertTrue(text.toString().isEmpty());
  }
}
