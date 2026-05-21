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

import de.soptim.opencgmes.sparql.validation.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.sparql.validation.shacl.EmbeddedSparql;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Phase 2 tests — SHACL-embedded SPARQL validation.
 */
public class ShaclValidationTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";

    private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
    private static final String CLASS_VOLTAGE_LEVEL = CIM + "VoltageLevel";
    private static final String PROP_NAME = CIM + "IdentifiedObject.name";
    private static final String PROP_R = CIM + "ACLineSegment.r";
    private static final String PROP_NOMINAL_V = CIM + "VoltageLevel.nominalVoltage";
    private static final String PROP_EQ_CONTAINER = CIM + "Equipment.EquipmentContainer";

    private static final String PROFILE_EQ = "http://example.org/profile/Equipment/1.0";
    private static final String PROFILE_TP = "http://example.org/profile/Topology/1.0";

    private SparqlValidationApi api;

    @Before
    public void setUp() {
        RdfsSchemaIndex index = RdfsSchemaIndex.builder()
                .addProfile(PROFILE_EQ,
                        List.of(CLASS_AC_LINE),
                        List.of(PROP_NAME, PROP_R, PROP_EQ_CONTAINER))
                .addProfile(PROFILE_TP,
                        List.of(CLASS_VOLTAGE_LEVEL),
                        List.of(PROP_NOMINAL_V))
                .build();
        api = new SparqlValidationApi(index);
    }

    @Test
    public void extractorFindsAllFourEmbeddings() {
        Graph g = loadShapes("shacl/shapes-basic.ttl");
        List<EmbeddedSparql> queries = api.extractShaclSparql(g);
        assertEquals("should find 1 ASK, 1 CONSTRUCT and 2 SELECT queries (target + constraint)",
                4, queries.size());

        long selects = queries.stream().filter(q -> q.kind() == EmbeddedSparql.Kind.SELECT).count();
        long asks = queries.stream().filter(q -> q.kind() == EmbeddedSparql.Kind.ASK).count();
        long constructs = queries.stream().filter(q -> q.kind() == EmbeddedSparql.Kind.CONSTRUCT).count();
        assertEquals(2, selects);
        assertEquals(1, asks);
        assertEquals(1, constructs);
    }

    @Test
    public void prefixesAreResolvedFromShPrefixes() {
        Graph g = loadShapes("shacl/shapes-basic.ttl");
        EmbeddedSparql first = api.extractShaclSparql(g).get(0);
        assertEquals("cim should resolve to the CIM100 namespace",
                "http://iec.ch/TC57/CIM100#", first.prefixes().get("cim"));
        assertTrue("renderedQuery must prepend the resolved PREFIX declaration",
                first.renderedQuery().contains("PREFIX cim: <http://iec.ch/TC57/CIM100#>"));
    }

    @Test
    public void validShapesGraphProducesNoErrors() {
        Graph g = loadShapes("shacl/shapes-basic.ttl");
        var r = api.validateShacl(g);
        assertTrue("expected no ERROR annotations in any embedded query; got: "
                + describe(r), r.isValid());
        assertEquals(4, r.embeddedResults().size());
    }

    @Test
    public void unknownClassInShaclQueryIsReported() {
        Graph g = loadShapes("shacl/shapes-unknown.ttl");
        var r = api.validateShacl(g);
        assertFalse(r.isValid());

        boolean foundClass = r.embeddedResults().stream()
                .flatMap(e -> e.result().annotations().stream())
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS
                        && a.term().getURI().equals(CIM + "NotAClass"));
        boolean foundProp = r.embeddedResults().stream()
                .flatMap(e -> e.result().annotations().stream())
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY
                        && a.term().getURI().equals(CIM + "notAProperty"));
        assertTrue("UNKNOWN_CLASS should be reported", foundClass);
        assertTrue("UNKNOWN_PROPERTY should be reported", foundProp);
    }

    @Test
    public void crossProfileShapeNeedsBothProfiles() {
        Graph g = loadShapes("shacl/shapes-cross-profile.ttl");

        Collection<VersionIri> profiles = api.getShaclProfileDependencies(g);
        assertEquals("shape uses cim:ACLineSegment.r (EQ) and cim:VoltageLevel.nominalVoltage (TP)",
                2, profiles.size());
        assertTrue(profiles.contains(VersionIri.of(PROFILE_EQ)));
        assertTrue(profiles.contains(VersionIri.of(PROFILE_TP)));
    }

    @Test
    public void shaclPropertyAndClassDependenciesAreInferred() {
        Graph g = loadShapes("shacl/shapes-basic.ttl");

        Collection<org.apache.jena.graph.Node> classes = api.getShaclClassDependencies(g);
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_AC_LINE)));
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_VOLTAGE_LEVEL)));

        Collection<org.apache.jena.graph.Node> props = api.getShaclPropertyDependencies(g);
        assertTrue(props.contains(NodeFactory.createURI(PROP_NAME)));
        assertTrue(props.contains(NodeFactory.createURI(PROP_R)));
        assertTrue(props.contains(NodeFactory.createURI(PROP_NOMINAL_V)));
    }

    @Test
    public void restrictedProfileDependenciesHidesOthers() {
        Graph g = loadShapes("shacl/shapes-cross-profile.ttl");
        Collection<VersionIri> restricted = api.getShaclProfileDependencies(
                g, List.of(VersionIri.of(PROFILE_EQ)));
        assertEquals(1, restricted.size());
        assertTrue(restricted.contains(VersionIri.of(PROFILE_EQ)));
    }

    @Test
    public void validateAgainstWrongProfileProducesErrors() {
        Graph g = loadShapes("shacl/shapes-cross-profile.ttl");
        // Validate the cross-profile shape against EQ only — VoltageLevel.nominalVoltage lives in TP.
        var r = api.validateShacl(g, List.of(VersionIri.of(PROFILE_EQ)));
        assertFalse(r.isValid());

        boolean hint = r.embeddedResults().stream()
                .flatMap(e -> e.result().annotations().stream())
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY
                        && a.term().getURI().equals(PROP_NOMINAL_V)
                        && a.foundInOtherProfiles().stream()
                                .anyMatch(v -> v.iri().equals(PROFILE_TP)));
        assertTrue("expected hint that VoltageLevel.nominalVoltage lives in TP", hint);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Graph loadShapes(String resourcePath) {
        try (InputStream in = ShaclValidationTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("missing resource: " + resourcePath);
            Graph g = GraphFactory.createDefaultGraph();
            RDFParser.fromString(new String(in.readAllBytes()), Lang.TURTLE).parse(g);
            return g;
        } catch (Exception e) {
            throw new RuntimeException("failed to load " + resourcePath, e);
        }
    }

    private static String describe(de.soptim.opencgmes.sparql.validation.shacl.ShaclValidationResult r) {
        var sb = new StringBuilder();
        for (var er : r.embeddedResults()) {
            for (var a : er.result().annotations()) {
                sb.append("\n  ").append(a.severity()).append(' ').append(a.code()).append(": ")
                  .append(a.message());
            }
        }
        return sb.toString();
    }
}
