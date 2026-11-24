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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import de.soptim.opencgmes.cimxmlcli.dataset.DatasetAssembler;
import de.soptim.opencgmes.cimxmlcli.output.OutputTarget;
import de.soptim.opencgmes.cimxmlcli.progress.ProgressListener;
import de.soptim.opencgmes.cimxmlcli.validation.ConversionContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Converts official ENTSO-E data from the git submodules. Skipped unless they are initialized
 * ({@code git submodule update --init --recursive}).
 */
public class EntsoeIntegrationTest {

  private static final Path PROFILES =
      Path.of("../cimvocabcheck/core/testing/entsoe/application-profiles-library/CGMES");
  private static final Path RDFS_30 = PROFILES.resolve("CurrentRelease/RDFS");
  private static final Path RDFS_2415 = PROFILES.resolve("PastReleases/v2-4/Original/RDFS");
  private static final Path ESPHEIM =
      Path.of("testing/entsoe/ReliCapGrid/Instance/Espheim/Grid/cimxml");

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static void assumeSubmodule(Path folder) {
    assumeTrue(
        folder + " is missing; run git submodule update --init --recursive",
        Files.isDirectory(folder));
  }

  /** Converts and reads the result back; returns the validated input. */
  private ConversionContext convertAndRoundtrip(Path rdfs, Path models) throws Exception {
    final var target = OutputTarget.of(tmp.getRoot().toPath().resolve("out.jsonld.gz"));
    final var context =
        Cimxml2JsonLdConverter.createDefault(false, ProgressListener.NONE)
            .convert(rdfs, models, target);

    final var expected = TestData.graphSizes(new DatasetAssembler().assemble(context));
    final var read = RDFDataMgr.loadDatasetGraph(target.path().toString());
    assertEquals(expected, TestData.graphSizes(read));
    return context;
  }

  @Test
  public void convertsReliCapGridEspheimWithCgmes30Rdfs() throws Exception {
    assumeSubmodule(RDFS_30);
    assumeSubmodule(ESPHEIM);
    // The library ships the header profile of 2019 and 2020; only one may be used
    final var rdfs = tmp.newFolder("rdfs").toPath();
    copyMatching(RDFS_30, rdfs, "RDFS2020.rdf");

    final var context = convertAndRoundtrip(rdfs, ESPHEIM);

    assertEquals(4, context.models().size());
    assertTrue(
        context.models().stream()
            .anyMatch(m -> m.warnings().countsByKind().containsKey("CIM UUID without dashes")));
  }

  @Test
  public void convertsTheMiniIgmWithTheOfficialCgmes2415Rdfs() throws Exception {
    assumeSubmodule(RDFS_2415);
    final var models = TestData.copyMiniModels(tmp.newFolder("models").toPath());

    // The folder is used unchanged, including the four EQ variants
    final var context = convertAndRoundtrip(RDFS_2415, models);

    assertEquals(5, context.models().size());
    // All four EQ variants are merged into one profile
    final var warning = context.warnings().getFirst();
    assertEquals(1, context.warnings().size());
    assertEquals(4, warning.location().split(" \\+ ").length);
    assertTrue(warning.message(), warning.message().contains("merged into one profile"));
  }

  private static void copyMatching(Path from, Path to, String suffix) throws IOException {
    try (Stream<Path> files = Files.list(from)) {
      for (var file : files.filter(f -> f.toString().endsWith(suffix)).toList()) {
        Files.copy(file, to.resolve(file.getFileName().toString()));
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
