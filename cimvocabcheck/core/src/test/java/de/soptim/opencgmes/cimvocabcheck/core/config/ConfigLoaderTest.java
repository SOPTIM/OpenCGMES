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

package de.soptim.opencgmes.cimvocabcheck.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.soptim.opencgmes.cimvocabcheck.core.ConfigTemplate;
import de.soptim.opencgmes.cimxml.graph.CimNamespaceFactoryRegistry;
import de.soptim.opencgmes.cimxml.graph.CimProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies {@code opencgmes.jsonc} discovery, the {@code cimvocabcheck} section, and comment
 * tolerance.
 */
public class ConfigLoaderTest {

  private static final String TEST_CUSTOM_NAMESPACE = "https://vendor.example.org/custom-cim#";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  /**
   * {@link CimNamespaceFactoryRegistry} is process-global; undo test registrations so they don't
   * leak into other tests running in the same JVM.
   */
  @After
  public void unregisterTestNamespaces() {
    CimNamespaceFactoryRegistry.unregisterProfileFactory(TEST_CUSTOM_NAMESPACE);
  }

  @Test
  public void generatedTemplateParsesWithNoSchemasConfigured() throws Exception {
    Path file = write(tmp.getRoot().toPath(), ConfigTemplate.defaultJson());
    CimvocabcheckConfig cfg = ConfigLoader.load(file);
    // The scaffold leaves schemas commented out -> empty -> syntax-only (no bundled default).
    assertTrue("schemas should be empty", cfg.schemas().isEmpty());
    assertNull("schemasDirectory should be unset", cfg.schemasDirectory());
    assertEquals("default", cfg.strictness());
  }

  @Test
  public void extractsCimvocabcheckSectionAndToleratesComments() throws Exception {
    String json =
        """
        {
          // a leading comment
          "cimvocabcheck": {
            "strictness": "strict",
            "namedGraphs": { "urn:uuid:eq": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"] },
          }
        }
        """;
    Path file = write(tmp.getRoot().toPath(), json);
    CimvocabcheckConfig cfg = ConfigLoader.load(file);
    assertEquals("strict", cfg.strictness());
    assertTrue(cfg.namedGraphs().containsKey("urn:uuid:eq"));
  }

  @Test
  public void missingSectionYieldsEmptyConfig() throws Exception {
    Path file = write(tmp.getRoot().toPath(), "{ \"otherTool\": { \"x\": 1 } }");
    CimvocabcheckConfig cfg = ConfigLoader.load(file);
    assertTrue(cfg.schemas().isEmpty());
    assertNull(cfg.schemasDirectory());
  }

  @Test
  public void discoverWalksUpToNearestConfig() throws Exception {
    Path root = tmp.getRoot().toPath();
    write(root, "{ \"cimvocabcheck\": { \"strictness\": \"pedantic\" } }");
    Path deep = Files.createDirectories(root.resolve("a/b/c"));
    Optional<Path> found = ConfigLoader.discoverFile(deep);
    assertTrue(found.isPresent());
    assertEquals(root.resolve("opencgmes.jsonc").toRealPath(), found.get().toRealPath());
  }

  @Test
  public void cimNamespacesEntryRegistersACustomProfileShape() throws Exception {
    String customNs = TEST_CUSTOM_NAMESPACE;
    String json =
        """
        {
          "cimvocabcheck": {
            "cimNamespaces": { "%s": "cim17" }
          }
        }
        """
            .formatted(customNs);
    Path file = write(tmp.getRoot().toPath(), json);

    ConfigLoader.load(file);

    assertTrue(
        "custom namespace should now have a registered CimProfile factory",
        CimNamespaceFactoryRegistry.hasProfileFactory(customNs));

    // The registered factory should actually parse a CIM17-shaped ontology using the custom
    // namespace, proving the shape (not just the namespace URI) was wired up correctly.
    String ttl =
        """
        @prefix owl:  <http://www.w3.org/2002/07/owl#> .
        @prefix dcat: <http://www.w3.org/ns/dcat#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix cim:  <%s> .

        <http://example.org/profiles/Custom> a owl:Ontology ;
            owl:versionIRI <http://example.org/profiles/Custom/1.0> ;
            dcat:keyword "CUSTOM" .

        cim:Widget a rdfs:Class .
        """
            .formatted(customNs);
    Model m = ModelFactory.createDefaultModel();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(m);

    CimProfile profile = CimProfile.wrap(m.getGraph());
    assertEquals(customNs, profile.getCimNamespace());
    assertTrue(
        profile.getOwlVersionIris().stream()
            .anyMatch(n -> n.getURI().equals("http://example.org/profiles/Custom/1.0")));
  }

  @Test
  public void unknownCimNamespaceShapeFailsWithAClearError() throws Exception {
    String json =
        """
        {
          "cimvocabcheck": {
            "cimNamespaces": { "https://vendor.example.org/other-cim#": "cim99" }
          }
        }
        """;
    Path file = write(tmp.getRoot().toPath(), json);

    try {
      ConfigLoader.load(file);
      fail("expected ConfigException for an unknown profile shape");
    } catch (ConfigLoader.ConfigException expected) {
      assertTrue("message should name the bad shape", expected.getMessage().contains("cim99"));
    }
  }

  @Test
  public void noCimNamespacesEntryLeavesRegistryUntouched() throws Exception {
    Path file =
        write(tmp.getRoot().toPath(), "{ \"cimvocabcheck\": { \"strictness\": \"strict\" } }");
    CimvocabcheckConfig cfg = ConfigLoader.load(file);
    assertFalse(cfg.hasCimNamespaces());
  }

  private static Path write(Path dir, String content) throws IOException {
    Path file = dir.resolve("opencgmes.jsonc");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }
}
