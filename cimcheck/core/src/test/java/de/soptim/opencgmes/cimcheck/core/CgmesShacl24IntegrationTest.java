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

package de.soptim.opencgmes.cimcheck.core;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests — SHACL validation against real ENTSO-E CGMES 2.4.15 shape files.
 *
 * <p>These tests are skipped automatically when the git submodule is not initialised.
 *
 * <p>The CGMES 2.4.15 profiles use CIM namespace {@code http://iec.ch/TC57/2013/CIM-schema-cim16#}.
 * Multiple Equipment profile variants (Core, CoreOperation, CoreShortCircuit,
 * CoreOperationShortCircuit) share the same six version IRIs; duplicate registrations are silently
 * skipped so the first-loaded (alphabetically) variant wins.
 *
 * <p>The Enhanced Equipment SHACL ({@code EquipmentProfile.ttl}) is a merged file that covers
 * constraints for Core, Operation <em>and</em> ShortCircuit functionality. Because the RDFS loading
 * skips duplicate Equipment profile variants, some ShortCircuit-only classes (e.g. {@code
 * GroundingImpedance}) will not appear in the loaded schema. Tests therefore do not assert "zero
 * UNKNOWN_CLASS" for the 2.4 SHACL; they instead verify that the validation pipeline runs cleanly
 * and the dependency APIs return sensible results for the classes that <em>are</em> present in the
 * loaded Equipment Core profile.
 */
public class CgmesShacl24IntegrationTest {

  private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

  private static final Path RDFS_DIR =
      Path.of("testing/entsoe/application-profiles-library/CGMES/PastReleases/v2-4/Original/RDFS");
  private static final Path SHACL_DIR =
      Path.of("testing/entsoe/application-profiles-library/CGMES/PastReleases/v2-4/Enhanced/SHACL");

  private static final String EQ_VERSION_IRI = "http://entsoe.eu/CIM/EquipmentCore/3/1";

  private static final String EQ_SHACL = "EquipmentProfile.ttl";

  private SparqlValidationApi api;

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue(
        "CGMES 2.4.15 submodule not initialised — skipping",
        Files.isDirectory(RDFS_DIR) && hasRdfFiles(RDFS_DIR));

    CimProfileRegistry registry = new CimProfileRegistryStd();
    RdfXmlParser parser = new RdfXmlParser();
    try (var stream = Files.list(RDFS_DIR)) {
      stream
          .filter(p -> p.toString().endsWith(".rdf"))
          .sorted()
          .forEach(
              path -> {
                try {
                  var profile = parser.parseCimProfile(path);
                  registry.register(profile);
                } catch (IllegalArgumentException e) {
                  // Duplicate version IRIs across Equipment profile variants — skip.
                } catch (IOException e) {
                  throw new RuntimeException("Failed to read " + path, e);
                }
              });
    }
    api = new SparqlValidationApi(RdfsSchemaIndex.fromCimRegistry(registry));
  }

  // ---- Equipment SHACL (structural constraints, no embedded SPARQL) ----------------

  @Test
  public void equipmentShapes_validatesWithoutException() throws IOException {
    // Smoke test: the full Equipment SHACL must parse and validate without throwing.
    // It will produce some UNKNOWN_CLASS and UNKNOWN_PROPERTY annotations for classes
    // from ShortCircuit-only variants (not loaded due to duplicate version IRI skipping)
    // and for rdf:type / CIM100 absolute URIs in inverse paths — but must not crash.
    Graph g = loadShacl(EQ_SHACL);
    var r = api.validateShacl(g);
    assertNotNull("validateShacl must return a non-null result", r);
  }

  @Test
  public void equipmentShapes_classDependenciesIncludeAcLineSegment() throws IOException {
    // ACLineSegment is in the Equipment Core RDFS and must be detected via sh:targetClass.
    Graph g = loadShacl(EQ_SHACL);
    Collection<org.apache.jena.graph.Node> classes = api.getShaclClassDependencies(g);
    assertTrue(
        "getShaclClassDependencies must include cim:ACLineSegment from sh:targetClass",
        classes.contains(NodeFactory.createURI(CIM16 + "ACLineSegment")));
  }

  @Test
  public void equipmentShapes_profileDependencyIncludesEq() throws IOException {
    // The Equipment SHACL must report a dependency on the EQ 2.4.15 profile.
    Graph g = loadShacl(EQ_SHACL);
    Collection<VersionIri> profiles = api.getShaclProfileDependencies(g);
    assertTrue(
        "Equipment SHACL must depend on the EQ 2.4.15 profile",
        profiles.contains(VersionIri.of(EQ_VERSION_IRI)));
  }

  @Test
  public void equipmentShapes_coreClassesHaveNoUnknownAnnotations() throws IOException {
    // Classes defined in the Equipment Core profile (the loaded variant) must not
    // appear in UNKNOWN_CLASS shape annotations.
    Graph g = loadShacl(EQ_SHACL);
    var r = api.validateShacl(g);

    // ACLineSegment, PowerTransformer, BusbarSection are core Equipment classes.
    var spurious =
        r.shapeAnnotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
            .filter(
                a ->
                    a.term() != null
                        && (a.term().getURI().equals(CIM16 + "ACLineSegment")
                            || a.term().getURI().equals(CIM16 + "PowerTransformer")
                            || a.term().getURI().equals(CIM16 + "BusbarSection")))
            .toList();
    assertTrue(
        "Core Equipment classes must not be reported as unknown: " + spurious, spurious.isEmpty());
  }

  // ---- helpers ---------------------------------------------------------------------------

  private Graph loadShacl(String filename) throws IOException {
    String text = Files.readString(SHACL_DIR.resolve(filename));
    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(text, Lang.TURTLE).parse(g);
    return g;
  }

  private static boolean hasRdfFiles(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      return stream.anyMatch(p -> p.toString().endsWith(".rdf"));
    }
  }
}
