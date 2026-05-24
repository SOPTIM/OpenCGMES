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

package de.soptim.opencgmes.sparql.validation;

import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import de.soptim.opencgmes.sparql.validation.schema.RdfsSchemaIndex;
import org.apache.jena.graph.NodeFactory;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Integration tests using the real ENTSO-E CGMES 2.4.15 RDFS profiles from the git submodule.
 *
 * <p>These tests are skipped automatically when the submodule is not initialised.</p>
 *
 * <p>The CGMES 2.4.15 profiles use CIM namespace {@code http://iec.ch/TC57/2013/CIM-schema-cim16#}.
 * Multiple Equipment profile variants (Core, CoreOperation, CoreShortCircuit,
 * CoreOperationShortCircuit) share the same six version IRIs; duplicate registrations are
 * silently skipped so the first-loaded variant wins.</p>
 */
public class Cgmes24IntegrationTest {

    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

    private static final Path RDFS_DIR = Path.of(
            "testing/entsoe/application-profiles-library/CGMES/PastReleases/v2-4/Original/RDFS");

    // Well-known version IRI for the CGMES 2.4.15 Topology profile (entsoeURI).
    private static final String TP_VERSION_IRI = "http://entsoe.eu/CIM/Topology/4/1";

    // Well-known version IRI for the CGMES 2.4.15 Equipment Core profile (entsoeURI).
    private static final String EQ_VERSION_IRI = "http://entsoe.eu/CIM/EquipmentCore/3/1";

    private static final String PREAMBLE =
            "PREFIX cim: <" + CIM16 + ">\n"
            + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";

    private SparqlValidationApi api;

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("CGMES 2.4.15 submodule not initialised — skipping",
                Files.isDirectory(RDFS_DIR) && hasRdfFiles(RDFS_DIR));

        CimProfileRegistry registry = new CimProfileRegistryStd();
        RdfXmlParser parser = new RdfXmlParser();
        try (var stream = Files.list(RDFS_DIR)) {
            stream.filter(p -> p.toString().endsWith(".rdf"))
                  .sorted()
                  .forEach(path -> {
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

    // ---- Schema index checks -------------------------------------------------------------------

    @Test
    public void topologyProfileVersionIriIsRegistered() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var v = VersionIri.of(TP_VERSION_IRI);
        assertNotNull("TP profile must be indexed under its entsoeURI", index.profiles().get(v));
    }

    @Test
    public void equipmentProfileVersionIriIsRegistered() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var v = VersionIri.of(EQ_VERSION_IRI);
        assertNotNull("EQ profile must be indexed under its entsoeURI", index.profiles().get(v));
    }

    @Test
    public void topologicalNodeClassIsKnown() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var v = VersionIri.of(TP_VERSION_IRI);
        var cls = NodeFactory.createURI(CIM16 + "TopologicalNode");
        assertTrue("TopologicalNode must be in the TP profile",
                index.profiles().get(v).classes().contains(cls));
    }

    @Test
    public void acLineSegmentClassIsKnown() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var v = VersionIri.of(EQ_VERSION_IRI);
        var cls = NodeFactory.createURI(CIM16 + "ACLineSegment");
        assertTrue("ACLineSegment must be in the EQ profile",
                index.profiles().get(v).classes().contains(cls));
    }

    @Test
    public void identifiedObjectNamePropertyIsKnown() {
        var index = (RdfsSchemaIndex) api.schemaIndex();
        var v = VersionIri.of(TP_VERSION_IRI);
        var prop = NodeFactory.createURI(CIM16 + "IdentifiedObject.name");
        assertTrue("IdentifiedObject.name must appear in the TP profile",
                index.profiles().get(v).properties().contains(prop));
    }

    // ---- SPARQL validation against CIM16 schema ------------------------------------------------

    @Test
    public void validQueryAgainstTopologyProfile_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { "
                + "  ?tn a cim:TopologicalNode ; "
                + "      cim:IdentifiedObject.name ?name . "
                + "}");
        assertNoErrors(r);
    }

    @Test
    public void unknownCim16Class_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:DoesNotExistInCIM16 . }");
        var a = expectSingle(r, SparqlValidationCode.UNKNOWN_CLASS);
        assertEquals(CIM16 + "DoesNotExistInCIM16", a.term().getURI());
        assertFalse(r.isValid());
    }

    @Test
    public void unknownCim16Property_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:IdentifiedObject.phantomProperty ?v . }");
        var a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(CIM16 + "IdentifiedObject.phantomProperty", a.term().getURI());
        assertFalse(r.isValid());
    }

    @Test
    public void validQueryAgainstEquipmentProfile_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { "
                + "  ?line a cim:ACLineSegment ; "
                + "        cim:IdentifiedObject.name ?name . "
                + "}");
        assertNoErrors(r);
    }

    @Test
    public void insertDataWithValidCim16Terms_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { "
                + "  <urn:node1> a cim:TopologicalNode ; "
                + "              cim:IdentifiedObject.name \"Node 1\" . "
                + "}");
        assertNoErrors(r);
    }

    @Test
    public void insertDataWithUnknownCim16Property_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { <urn:x> cim:NonExistent.property \"val\" . }");
        expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertFalse(r.isValid());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static boolean hasRdfFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".rdf"));
        }
    }

    private static void assertNoErrors(SparqlValidationResult r) {
        var errors = r.annotations().stream()
                .filter(a -> a.severity() == SparqlValidationSeverity.ERROR)
                .toList();
        assertTrue("expected no ERROR annotations, got: " + errors, errors.isEmpty());
        assertTrue(r.isValid());
    }

    private static SparqlValidationAnnotation expectSingle(
            SparqlValidationResult r, SparqlValidationCode code) {
        var matches = r.annotations().stream()
                .filter(a -> a.code() == code)
                .toList();
        assertEquals("expected exactly one annotation with code " + code + ", got: "
                + r.annotations(), 1, matches.size());
        return matches.get(0);
    }
}
