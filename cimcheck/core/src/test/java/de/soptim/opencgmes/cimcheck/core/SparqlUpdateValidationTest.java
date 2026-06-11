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

import de.soptim.opencgmes.cimcheck.core.analysis.GraphReference;
import de.soptim.opencgmes.cimcheck.core.analysis.SparqlUpdateAnalysis;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for SPARQL Update validation: INSERT DATA, DELETE WHERE, INSERT/DELETE ... WHERE,
 * CREATE GRAPH, DROP GRAPH, and multi-operation requests separated by {@code ;}.
 */
public class SparqlUpdateValidationTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";
    private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
    private static final String PROP_NAME = CIM + "IdentifiedObject.name";
    private static final String PROP_R    = CIM + "ACLineSegment.r";
    private static final String PROFILE_EQ = "http://example.org/profile/Equipment/1.0";

    private static final String PREAMBLE =
            "PREFIX cim: <" + CIM + ">\n"
            + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";

    private SparqlValidationApi api;

    @Before
    public void setUp() {
        RdfsSchemaIndex index = RdfsSchemaIndex.builder()
                .addProfile(PROFILE_EQ,
                        List.of(CLASS_AC_LINE),
                        List.of(PROP_NAME, PROP_R))
                .build();
        api = new SparqlValidationApi(index);
    }

    // ---- INSERT DATA -----------------------------------------------------------------------

    @Test
    public void insertDataWithValidTriples_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA {\n"
                + "  <urn:line1> a cim:ACLineSegment ;\n"
                + "              cim:IdentifiedObject.name \"Line 1\" ;\n"
                + "              cim:ACLineSegment.r \"0.01\"^^xsd:float .\n"
                + "}");
        assertNoErrors(r);
        assertNull("update result should have no query plan", r.queryPlan());
    }

    @Test
    public void insertDataWithUnknownProperty_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { <urn:x> cim:doesNotExist \"val\" . }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(SparqlValidationSeverity.ERROR, a.severity());
        assertEquals(CIM + "doesNotExist", a.term().getURI());
        assertFalse(r.isValid());
    }

    @Test
    public void insertDataWithUnknownClass_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { <urn:x> a cim:NoSuchClass . }");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_CLASS);
        assertEquals(CIM + "NoSuchClass", a.term().getURI());
    }

    // ---- DELETE DATA -----------------------------------------------------------------------

    @Test
    public void deleteDataWithValidTriples_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "DELETE DATA { <urn:line1> a cim:ACLineSegment . }");
        assertNoErrors(r);
    }

    @Test
    public void deleteDataWithUnknownProperty_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "DELETE DATA { <urn:x> cim:phantom \"val\" . }");
        expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
    }

    // ---- DELETE WHERE ----------------------------------------------------------------------

    @Test
    public void deleteWhereWithValidPattern_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "DELETE WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . }");
        assertNoErrors(r);
    }

    @Test
    public void deleteWhereWithUnknownProperty_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "DELETE WHERE { ?s cim:doesNotExist ?o . }");
        expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
    }

    // ---- INSERT/DELETE ... WHERE -----------------------------------------------------------

    @Test
    public void insertDeleteWhereWithValidTerms_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "DELETE { ?s cim:ACLineSegment.r ?old }\n"
                + "INSERT { ?s cim:ACLineSegment.r \"0.02\"^^xsd:float }\n"
                + "WHERE  { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?old . }");
        assertNoErrors(r);
    }

    @Test
    public void insertWhereUnknownPropertyInTemplate_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT { ?s cim:phantom ?x }\n"
                + "WHERE  { ?s a cim:ACLineSegment . BIND(1 AS ?x) }");
        expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
    }

    @Test
    public void deleteWhereUnknownPropertyInWhereClause_emitsError() {
        var r = api.validateSparql(PREAMBLE
                + "DELETE { ?s cim:ACLineSegment.r ?r }\n"
                + "WHERE  { ?s a cim:ACLineSegment ; cim:phantom ?r . }");
        expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
    }

    // ---- CREATE / DROP GRAPH ---------------------------------------------------------------

    @Test
    public void createGraph_noSchemaIssues() {
        var r = api.validateSparql("CREATE GRAPH <http://example.com/result>");
        assertNoErrors(r);
        assertTrue(r.isValid());
        assertNull("update result should have no query plan", r.queryPlan());
    }

    @Test
    public void dropGraph_noSchemaIssues() {
        var r = api.validateSparql("DROP GRAPH <http://example.com/result>");
        assertNoErrors(r);
    }

    @Test
    public void createGraphReferenceIsTracked() throws InvalidQueryException {
        Collection<Node> graphs = api.getUpdateGraphDependencies(
                "CREATE GRAPH <http://example.com/g>");
        assertTrue(graphs.contains(NodeFactory.createURI("http://example.com/g")));
    }

    @Test
    public void dropGraphReferenceIsTracked() throws InvalidQueryException {
        Collection<Node> graphs = api.getUpdateGraphDependencies(
                "DROP GRAPH <http://example.com/g>");
        assertTrue(graphs.contains(NodeFactory.createURI("http://example.com/g")));
    }

    // ---- Multiple operations separated by ";" ----------------------------------------------

    @Test
    public void multipleOperations_allValidated() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { <urn:a> a cim:ACLineSegment } ;\n"
                + "DELETE WHERE { ?s cim:doesNotExist ?v }");
        // The second operation contains an unknown property → at least one error.
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.UNKNOWN_PROPERTY);
        assertEquals(CIM + "doesNotExist", a.term().getURI());
    }

    @Test
    public void multipleOperations_allValidAnnotated() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { <urn:a> cim:missingProp1 \"x\" } ;\n"
                + "DELETE WHERE { ?s a cim:NoSuchClass }");
        long errors = r.annotations().stream()
                .filter(a -> a.severity() == SparqlValidationSeverity.ERROR)
                .count();
        assertEquals("both operations should produce errors", 2, errors);
    }

    @Test
    public void multipleOperations_allValid() {
        var r = api.validateSparql(PREAMBLE
                + "INSERT DATA { <urn:a> a cim:ACLineSegment } ;\n"
                + "DELETE WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r } ;\n"
                + "CREATE GRAPH <http://example.com/g>");
        assertNoErrors(r);
    }

    // ---- Graph reference tracking in UPDATE templates --------------------------------------

    @Test
    public void insertIntoNamedGraph_graphIsTracked() throws InvalidQueryException {
        Collection<Node> graphs = api.getUpdateGraphDependencies(PREAMBLE
                + "INSERT DATA { GRAPH <http://ex.com/g> { <urn:a> a cim:ACLineSegment } }");
        assertTrue(graphs.contains(NodeFactory.createURI("http://ex.com/g")));
    }

    // ---- Update dependency methods ---------------------------------------------------------

    @Test
    public void getUpdatePropertyDependencies() throws InvalidQueryException {
        Collection<Node> props = api.getUpdatePropertyDependencies(PREAMBLE
                + "INSERT DATA { <urn:a> cim:ACLineSegment.r \"0.01\"^^xsd:float . }");
        assertTrue(props.contains(NodeFactory.createURI(PROP_R)));
    }

    @Test
    public void getUpdateClassDependencies() throws InvalidQueryException {
        Collection<Node> classes = api.getUpdateClassDependencies(PREAMBLE
                + "INSERT DATA { <urn:a> a cim:ACLineSegment . }");
        assertTrue(classes.contains(NodeFactory.createURI(CLASS_AC_LINE)));
    }

    @Test
    public void getUpdateProfileDependencies() throws InvalidQueryException {
        Collection<VersionIri> profiles = api.getUpdateProfileDependencies(PREAMBLE
                + "INSERT DATA { <urn:a> a cim:ACLineSegment ; cim:ACLineSegment.r \"0.1\"^^xsd:float }");
        assertTrue(profiles.contains(VersionIri.of(PROFILE_EQ)));
    }

    @Test
    public void getUpdateProfileDependencies_scopedToProfileList() throws InvalidQueryException {
        String u = PREAMBLE
                + "INSERT DATA { <urn:a> a cim:ACLineSegment ; cim:ACLineSegment.r \"0.1\"^^xsd:float }";
        // EQ in scope → profile is reported.
        Collection<VersionIri> inScope =
                api.getUpdateProfileDependencies(u, List.of(VersionIri.of(PROFILE_EQ)));
        assertTrue(inScope.contains(VersionIri.of(PROFILE_EQ)));
        // Empty scope → nothing reported.
        Collection<VersionIri> empty = api.getUpdateProfileDependencies(u, List.of());
        assertTrue("no profiles in scope → empty result", empty.isEmpty());
    }

    @Test
    public void getUpdateProfileDependencies_scopedToNamedGraphMap() throws InvalidQueryException {
        Node graphEq = NodeFactory.createURI("urn:graph:eq");
        Map<Node, Collection<VersionIri>> map = Map.of(graphEq, List.of(VersionIri.of(PROFILE_EQ)));
        String u = PREAMBLE
                + "INSERT DATA { GRAPH <urn:graph:eq> { <urn:a> a cim:ACLineSegment } }";
        Collection<VersionIri> profiles = api.getUpdateProfileDependencies(u, map);
        assertTrue(profiles.contains(VersionIri.of(PROFILE_EQ)));
    }

    // ---- Syntax error handling -------------------------------------------------------------

    @Test
    public void syntaxError_notAQueryOrUpdate_returnsSyntaxAnnotation() {
        var r = api.validateSparql("this is not sparql at all !! ###");
        SparqlValidationAnnotation a = expectSingle(r, SparqlValidationCode.SYNTAX_ERROR);
        assertEquals(SparqlValidationSeverity.ERROR, a.severity());
        assertNull("no query plan on syntax error", r.queryPlan());
    }

    // ---- Auto-detection: SELECT still works ------------------------------------------------

    @Test
    public void selectQueryStillParsedAsQuery() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r }");
        assertNoErrors(r);
        assertNotNull("SELECT result should have a query plan", r.queryPlan());
    }

    // ---- SparqlUpdateAnalysis via analyzeUpdate --------------------------------------------

    @Test
    public void analyzeUpdate_returnsCorrectGraphSources() throws InvalidQueryException {
        var validator = new SparqlQueryValidator(
                RdfsSchemaIndex.builder()
                        .addProfile(PROFILE_EQ, List.of(CLASS_AC_LINE), List.of(PROP_R))
                        .build());
        SparqlUpdateAnalysis a = validator.analyzeUpdate(PREAMBLE
                + "INSERT DATA { GRAPH <http://ex.com/g> { <urn:a> a cim:ACLineSegment } } ;\n"
                + "CREATE GRAPH <http://ex.com/h>");

        long templateGraphs = a.graphs().stream()
                .filter(g -> g.source() == GraphReference.Source.GRAPH_BLOCK)
                .count();
        assertTrue("INSERT GRAPH block should be tracked", templateGraphs >= 1);

        long mgmtGraphs = a.graphs().stream()
                .filter(g -> g.source() == GraphReference.Source.UPDATE_MANAGEMENT)
                .count();
        assertEquals("CREATE GRAPH should produce one management ref", 1, mgmtGraphs);
    }

    // ---- WITH graph scope (P1 fix) ---------------------------------------------------------

    @Test
    public void withGraphScopeAppliedToWhereAndTemplates() {
        // Two-profile setup: eq graph → EQ profile (ACLineSegment), tp graph → TP profile (VoltageLevel).
        String profileEq = PROFILE_EQ;
        String profileTp = "http://example.org/profile/Topology/1.0";
        String classVl   = CIM + "VoltageLevel";
        RdfsSchemaIndex twoProfileIndex = RdfsSchemaIndex.builder()
                .addProfile(profileEq, List.of(CLASS_AC_LINE), List.of(PROP_NAME, PROP_R))
                .addProfile(profileTp, List.of(classVl),       List.of())
                .build();
        SparqlValidationApi twoApi = new SparqlValidationApi(twoProfileIndex);

        Node eqGraph = NodeFactory.createURI("urn:graph:eq");
        java.util.Map<Node, Collection<VersionIri>> scope = java.util.Map.of(
                eqGraph, List.of(VersionIri.of(profileEq)));

        // WITH <urn:graph:eq> scopes to EQ; VoltageLevel lives only in TP → UNKNOWN_CLASS.
        var r = twoApi.validateSparql(PREAMBLE
                + "WITH <urn:graph:eq>\n"
                + "DELETE { ?s a cim:ACLineSegment }\n"
                + "INSERT { ?s a cim:VoltageLevel }\n"
                + "WHERE  { ?s a cim:ACLineSegment }", scope);
        boolean foundUnknownClass = r.annotations().stream()
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS
                        && a.term().getURI().equals(classVl));
        assertTrue("VoltageLevel must be unknown in WITH <eq> scope, got: " + r.annotations(),
                foundUnknownClass);
    }

    @Test
    public void withGraphDoesNotScopeExplicitVariableGraphTemplates() {
        // WITH <eq> must not be applied to GRAPH ?g { ... } templates — variable graphs are
        // dynamic and should fall back to union scope, not be attributed to the WITH IRI.
        // The repro: VoltageLevel lives only in TP; the template targets GRAPH ?g (not EQ).
        // Before the fix, VoltageLevel was incorrectly reported as UNKNOWN_CLASS in EQ scope.
        String profileTp = "http://example.org/profile/Topology/1.0";
        String classVl   = CIM + "VoltageLevel";
        RdfsSchemaIndex twoProfileIndex = RdfsSchemaIndex.builder()
                .addProfile(PROFILE_EQ, List.of(CLASS_AC_LINE), List.of(PROP_NAME, PROP_R))
                .addProfile(profileTp,  List.of(classVl),       List.of())
                .build();
        SparqlValidationApi twoApi = new SparqlValidationApi(twoProfileIndex);

        Node eqGraph = NodeFactory.createURI("urn:graph:eq");
        Node tpGraph = NodeFactory.createURI("urn:graph:tp");
        java.util.Map<Node, Collection<VersionIri>> scope = java.util.Map.of(
                eqGraph, List.of(VersionIri.of(PROFILE_EQ)),
                tpGraph, List.of(VersionIri.of(profileTp)));

        // GRAPH ?g in the INSERT template is a variable graph — must NOT be attributed to
        // the WITH IRI and must NOT produce a false UNKNOWN_CLASS for VoltageLevel.
        var r = twoApi.validateSparql(PREAMBLE
                + "WITH <urn:graph:eq>\n"
                + "INSERT { GRAPH ?g { ?s a cim:VoltageLevel } }\n"
                + "WHERE  { BIND(<urn:graph:tp> AS ?g) . ?s a cim:ACLineSegment }", scope);
        long unknownClassErrors = r.annotations().stream()
                .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
                .count();
        assertEquals("GRAPH ?g template must not be attributed to WITH scope; got: "
                + r.annotations(), 0, unknownClassErrors);
    }

    @Test
    public void withGraphValidTermsProduceNoErrors() {
        Node eqGraph = NodeFactory.createURI("urn:graph:eq");
        java.util.Map<Node, Collection<VersionIri>> scope = java.util.Map.of(
                eqGraph, List.of(VersionIri.of(PROFILE_EQ)));
        var r = api.validateSparql(PREAMBLE
                + "WITH <urn:graph:eq>\n"
                + "DELETE { ?s cim:ACLineSegment.r ?old }\n"
                + "INSERT { ?s cim:ACLineSegment.r \"0.02\"^^xsd:float }\n"
                + "WHERE  { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?old }", scope);
        assertNoErrors(r);
    }

    // ---- Graph dependency tracking: CLEAR, LOAD, COPY/MOVE/ADD (P2 fix) -------------------

    @Test
    public void clearGraphReferenceIsTracked() throws InvalidQueryException {
        Collection<Node> graphs = api.getUpdateGraphDependencies(
                "CLEAR GRAPH <http://example.com/g>");
        assertTrue(graphs.contains(NodeFactory.createURI("http://example.com/g")));
    }

    @Test
    public void loadIntoGraphReferenceIsTracked() throws InvalidQueryException {
        Collection<Node> graphs = api.getUpdateGraphDependencies(
                "LOAD <http://example.com/data.ttl> INTO GRAPH <http://example.com/dest>");
        assertTrue(graphs.contains(NodeFactory.createURI("http://example.com/dest")));
    }

    @Test
    public void copyGraphReferencesAreTracked() throws InvalidQueryException {
        Collection<Node> graphs = api.getUpdateGraphDependencies(
                "COPY GRAPH <http://example.com/src> TO GRAPH <http://example.com/dst>");
        assertTrue(graphs.contains(NodeFactory.createURI("http://example.com/src")));
        assertTrue(graphs.contains(NodeFactory.createURI("http://example.com/dst")));
    }

    // ---- helpers ---------------------------------------------------------------------------

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
