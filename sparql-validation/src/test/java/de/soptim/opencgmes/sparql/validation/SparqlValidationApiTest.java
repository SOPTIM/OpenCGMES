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
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Phase 1 acceptance tests for {@link SparqlValidationApi} — covers the 16 scenarios listed
 * in the module README.
 */
public class SparqlValidationApiTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";
    private static final String EX = "http://example.org/";

    private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
    private static final String CLASS_VOLTAGE_LEVEL = CIM + "VoltageLevel";
    private static final String PROP_NAME = CIM + "IdentifiedObject.name";
    private static final String PROP_R = CIM + "ACLineSegment.r";
    private static final String PROP_NOMINAL_V = CIM + "VoltageLevel.nominalVoltage";

    private static final String PROFILE_EQ = "http://example.org/profile/Equipment/1.0";
    private static final String PROFILE_TP = "http://example.org/profile/Topology/1.0";

    private static final String PREAMBLE =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX cim: <" + CIM + ">\n"
            + "PREFIX ex:  <" + EX + ">\n";

    private SparqlValidationApi api;

    @Before
    public void setUp() {
        // EQ profile owns ACLineSegment + its properties; TP profile owns VoltageLevel + its props.
        RdfsSchemaIndex index = RdfsSchemaIndex.builder()
                .addProfile(PROFILE_EQ,
                        List.of(CLASS_AC_LINE),
                        List.of(PROP_NAME, PROP_R))
                .addProfile(PROFILE_TP,
                        List.of(CLASS_VOLTAGE_LEVEL),
                        List.of(PROP_NOMINAL_V))
                .build();
        api = new SparqlValidationApi(index);
    }

    // 1. Valid query against all profiles.
    @Test
    public void validQueryAgainstAllProfiles() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . }");
        assertNoErrors(r);
        assertNotNull("query plan should be present", r.queryPlan());
        assertTrue("plan should mention BGP", r.queryPlan().contains("bgp") || r.queryPlan().contains("BGP"));
    }

    // 2. Valid query against selected profiles.
    @Test
    public void validQueryAgainstSelectedProfile() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . }",
                List.of(VersionIri.of(PROFILE_EQ)));
        assertNoErrors(r);
    }

    // 3. Unknown class → ERROR UNKNOWN_CLASS.
    @Test
    public void unknownClassEmitsError() {
        var r = api.validateSparql(PREAMBLE + "SELECT * WHERE { ?x a cim:DoesNotExist . }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_CLASS);
        assertEquals(SparqlValidationSeverity.ERROR, a.severity());
        assertEquals(CIM + "DoesNotExist", a.term().getURI());
        assertFalse(r.isValid());
    }

    // 4. Unknown property → ERROR UNKNOWN_PROPERTY.
    @Test
    public void unknownPropertyEmitsError() {
        var r = api.validateSparql(PREAMBLE + "SELECT * WHERE { ?x cim:doesNotExist ?y . }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(SparqlValidationSeverity.ERROR, a.severity());
        assertEquals(CIM + "doesNotExist", a.term().getURI());
    }

    // 5. Property exists in another profile.
    @Test
    public void propertyExistsInOtherProfile() {
        // Validate against EQ; nominalVoltage only lives in TP.
        var r = api.validateSparql(PREAMBLE + "SELECT * WHERE { ?x cim:VoltageLevel.nominalVoltage ?v . }",
                List.of(VersionIri.of(PROFILE_EQ)));
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(1, a.foundInOtherProfiles().size());
        assertEquals(PROFILE_TP, a.foundInOtherProfiles().iterator().next().iri());
        assertTrue("message should hint at TP",
                a.message().contains(PROFILE_TP));
    }

    // 6. Class exists in another profile.
    @Test
    public void classExistsInOtherProfile() {
        var r = api.validateSparql(PREAMBLE + "SELECT * WHERE { ?x a cim:VoltageLevel . }",
                List.of(VersionIri.of(PROFILE_EQ)));
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_CLASS);
        assertEquals(PROFILE_TP, a.foundInOtherProfiles().iterator().next().iri());
    }

    // 7. Named graph scope (GRAPH block validated against mapped profiles).
    @Test
    public void namedGraphScopeUsesMappedProfiles() {
        Node graphA = NodeFactory.createURI("urn:graph:a");
        Map<Node, Collection<VersionIri>> map = Map.of(
                graphA, List.of(VersionIri.of(PROFILE_EQ)));
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { GRAPH <urn:graph:a> { ?x a cim:ACLineSegment . } }", map);
        assertNoErrors(r);

        // Same query, but the class only lives in TP → should now fail.
        Map<Node, Collection<VersionIri>> mapB = Map.of(
                graphA, List.of(VersionIri.of(PROFILE_EQ)));
        var r2 = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { GRAPH <urn:graph:a> { ?x a cim:VoltageLevel . } }", mapB);
        SparqlValidationAnnotation a = expectSingle(r2, SparqlValidationCode.UNKNOWN_CLASS);
        assertEquals(graphA, a.graph());
    }

    // 8. Graph used but not configured → GRAPH_NOT_CONFIGURED.
    @Test
    public void graphUsedButNotConfigured() {
        Node knownGraph = NodeFactory.createURI("urn:graph:a");
        Map<Node, Collection<VersionIri>> map = Map.of(
                knownGraph, List.of(VersionIri.of(PROFILE_EQ)));
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { GRAPH <urn:graph:unknown> { ?x a cim:ACLineSegment . } }", map);
        boolean found = r.annotations().stream()
                .anyMatch(a -> a.code() == SparqlValidationCode.GRAPH_NOT_CONFIGURED);
        assertTrue("expected GRAPH_NOT_CONFIGURED annotation, got: " + r.annotations(), found);
    }

    // 9. OPTIONAL block.
    @Test
    public void optionalBlockIsAnalyzed() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment . OPTIONAL { ?s cim:doesNotExist ?x . } }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(CIM + "doesNotExist", a.term().getURI());
    }

    // 10. UNION with properties/classes in both branches.
    @Test
    public void unionBothBranchesAnalyzed() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { { ?s a cim:DoesNotExist . } UNION { ?s cim:alsoMissing ?x . } }");
        long errors = r.annotations().stream()
                .filter(a -> a.severity() == SparqlValidationSeverity.ERROR)
                .count();
        assertEquals("expected 2 errors (UNKNOWN_CLASS + UNKNOWN_PROPERTY)", 2, errors);
    }

    // 11. FILTER EXISTS with class/property references.
    @Test
    public void filterExistsIsAnalyzed() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment "
                + "  FILTER EXISTS { ?s cim:doesNotExist ?z . } }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(CIM + "doesNotExist", a.term().getURI());
    }

    // 12. Subquery.
    @Test
    public void subqueryIsAnalyzed() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { { SELECT ?s WHERE { ?s a cim:DoesNotExist . } } }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_CLASS);
        assertEquals(CIM + "DoesNotExist", a.term().getURI());
    }

    // 13. Property path with a misspelled property segment.
    @Test
    public void propertyPathSegmentIsValidated() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:ACLineSegment.r / cim:typo ?o . }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(CIM + "typo", a.term().getURI());
    }

    // 14. Variable predicate.
    @Test
    public void variablePredicateProducesWarning() {
        var r = api.validateSparql(PREAMBLE + "SELECT * WHERE { ?s ?p ?o . }");
        boolean warn = r.annotations().stream()
                .anyMatch(a -> a.code() == SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY
                        && a.severity() == SparqlValidationSeverity.WARN);
        assertTrue("expected UNSUPPORTED_DYNAMIC_PROPERTY warning", warn);
        assertTrue("no ERROR annotations expected", r.isValid());
    }

    // 15. Syntax error.
    @Test
    public void syntaxErrorBecomesAnnotation() {
        var r = api.validateSparql("SELECT WHERE { ? }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.SYNTAX_ERROR);
        assertEquals(SparqlValidationSeverity.ERROR, a.severity());
        assertNull("no query plan when parsing failed", r.queryPlan());
    }

    // 16. Dependency extraction.
    @Test
    public void dependencyMethodsReturnExpectedSets() throws InvalidQueryException {
        String q = PREAMBLE
                + "SELECT * FROM NAMED <urn:graph:a> WHERE { "
                + "GRAPH <urn:graph:b> { "
                + "  ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . "
                + "  ?v a cim:VoltageLevel ; cim:VoltageLevel.nominalVoltage ?nv . "
                + "} }";

        Collection<Node> classes = api.getClassDependencies(q);
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_AC_LINE)));
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_VOLTAGE_LEVEL)));
        assertEquals(2, classes.size());

        Collection<Node> props = api.getPropertyDependencies(q);
        assertTrue(props.contains(NodeFactory.createURI(PROP_R)));
        assertTrue(props.contains(NodeFactory.createURI(PROP_NOMINAL_V)));
        assertEquals(2, props.size());

        Collection<Node> graphs = api.getGraphDependencies(q);
        assertTrue(graphs.contains(NodeFactory.createURI("urn:graph:a")));
        assertTrue(graphs.contains(NodeFactory.createURI("urn:graph:b")));

        Collection<VersionIri> profiles = api.getProfileDependencies(q);
        assertEquals(2, profiles.size());
        assertTrue(profiles.contains(VersionIri.of(PROFILE_EQ)));
        assertTrue(profiles.contains(VersionIri.of(PROFILE_TP)));
    }

    // ---- helpers -------------------------------------------------------------------------

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
