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
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for algebra-traversal completeness.
 *
 * <p>Each test pins a specific Jena algebra op type or SPARQL construct variant and would fail
 * if the corresponding branch is removed from {@code AlgebraAnalysisVisitor} or
 * {@code collectPathUris()}.</p>
 *
 * <p>Coverage map:</p>
 * <ul>
 *   <li>Property paths: P_ReverseLink, P_Alt, P_ZeroOrMoreN, P_OneOrMoreN,
 *       P_ZeroOrOne, P_NegPropSet, P_Mod, P_Seq</li>
 *   <li>Query modifiers: OpDistinct, OpReduced, OpOrder, OpSlice, OpGroup</li>
 *   <li>Query forms: ASK, DESCRIBE, CONSTRUCT WHERE</li>
 *   <li>Inline data: OpTable (VALUES)</li>
 *   <li>Remote endpoints: OpService</li>
 *   <li>Negative patterns: FILTER NOT EXISTS, MINUS</li>
 *   <li>Multi-segment SELECT (;-separated)</li>
 * </ul>
 */
public class AlgebraTraversalTest {

    private static final String CIM = "http://iec.ch/TC57/CIM100#";
    private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
    private static final String PROP_R        = CIM + "ACLineSegment.r";
    private static final String PROP_NAME     = CIM + "IdentifiedObject.name";
    private static final String PROFILE       = "http://example.org/profile/1.0";

    private static final String PREAMBLE =
            "PREFIX cim: <" + CIM + ">\n"
            + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n";

    private SparqlValidationApi api;

    @Before
    public void setUp() {
        RdfsSchemaIndex index = RdfsSchemaIndex.builder()
                .addProfile(PROFILE, List.of(CLASS_AC_LINE), List.of(PROP_R, PROP_NAME))
                .build();
        api = new SparqlValidationApi(index);
    }

    // ============================================================================================
    // Property paths — pins collectPathUris() branches in AlgebraAnalysisVisitor
    // ============================================================================================

    /** Reverse link path {@code ^cim:prop} — pins P_ReverseLink / P_Inverse handling. */
    @Test
    public void reverseLinkPath_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?o ^cim:doesNotExist ?s . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Alternative path {@code cim:p1|cim:p2}: unknown second branch — pins P_Alt right recursion. */
    @Test
    public void alternativePath_unknownSecondBranchDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:ACLineSegment.r|cim:doesNotExist ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Alternative path {@code cim:p1|cim:p2}: unknown first branch — pins P_Alt left recursion. */
    @Test
    public void alternativePath_unknownFirstBranchDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist|cim:ACLineSegment.r ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Zero-or-more path {@code cim:p*} — pins P_ZeroOrMoreN / P_ZeroOrMore1 handling. */
    @Test
    public void zeroOrMorePath_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist* ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** One-or-more path {@code cim:p+} — pins P_OneOrMoreN / P_OneOrMore1 handling. */
    @Test
    public void oneOrMorePath_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist+ ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Zero-or-one path {@code cim:p?} — pins P_ZeroOrOne handling. */
    @Test
    public void zeroOrOnePath_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist? ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Negated property set {@code !(cim:prop)} — pins P_NegPropSet handling. */
    @Test
    public void negatedPropertySet_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s !(cim:doesNotExist) ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Fixed-range quantifier path {@code cim:p{1,3}} — pins P_Mod sub-path recursion. */
    @Test
    public void fixedRangeQuantifierPath_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist{1,3} ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Valid property path produces no errors (sanity). */
    @Test
    public void validPropertyPath_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:ACLineSegment.r* ?o . }");
        assertNoErrors(r);
    }

    // ============================================================================================
    // Query modifiers (Op1 wrappers) — pins the generic Op1 fallback in analyze()
    // ============================================================================================

    /** SELECT DISTINCT — wraps algebra in OpDistinct, an Op1 subclass. */
    @Test
    public void selectDistinct_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT DISTINCT * WHERE { ?s a cim:DoesNotExist . }");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** SELECT REDUCED — wraps algebra in OpReduced, an Op1 subclass. */
    @Test
    public void selectReduced_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT REDUCED * WHERE { ?s a cim:DoesNotExist . }");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** ORDER BY — wraps algebra in OpOrder, an Op1 subclass. */
    @Test
    public void orderBy_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:DoesNotExist . } ORDER BY ?s");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** LIMIT — wraps algebra in OpSlice, an Op1 subclass. */
    @Test
    public void limit_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s a cim:DoesNotExist . } LIMIT 10");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** GROUP BY — wraps algebra in OpGroup, an Op1 subclass. */
    @Test
    public void groupBy_unknownClassInWhereDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT ?s (COUNT(*) AS ?c) WHERE { ?s a cim:DoesNotExist . } GROUP BY ?s");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** Stacked modifiers: ORDER BY + LIMIT both add Op1 wrappers. */
    @Test
    public void orderByAndLimit_unknownPropertyDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { ?s cim:doesNotExist ?o . } ORDER BY ?s LIMIT 5");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    // ============================================================================================
    // Query forms: ASK, DESCRIBE, CONSTRUCT WHERE
    // ============================================================================================

    /** ASK — Jena compiles the WHERE clause into the same Op tree as SELECT. */
    @Test
    public void askQuery_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "ASK WHERE { ?s a cim:DoesNotExist . }");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** ASK with valid terms — no errors (sanity). */
    @Test
    public void askQuery_validTerms_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "ASK WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . }");
        assertNoErrors(r);
    }

    /** DESCRIBE — WHERE clause is compiled to the Op tree and must be validated. */
    @Test
    public void describeQuery_unknownPropertyInWhereDetected() {
        var r = api.validateSparql(PREAMBLE
                + "DESCRIBE ?s WHERE { ?s cim:doesNotExist ?o . }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /**
     * CONSTRUCT WHERE (compact form) — the WHERE pattern doubles as the template; both the
     * WHERE Op tree and the template quads walk must catch the unknown class.
     */
    @Test
    public void constructWhere_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "CONSTRUCT WHERE { ?s a cim:DoesNotExist . }");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** CONSTRUCT WHERE with valid terms — no errors. */
    @Test
    public void constructWhere_validTerms_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "CONSTRUCT WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . }");
        assertNoErrors(r);
    }

    // ============================================================================================
    // VALUES inline data (OpTable) — must not crash or block adjacent BGP validation
    // ============================================================================================

    /** VALUES alone with IRI data — no false positive, no crash. */
    @Test
    public void valuesAlone_noFalsePositive() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { VALUES ?x { <urn:ex> } }");
        assertNoErrors(r);
    }

    /** VALUES followed by a BGP that uses an unknown class — BGP must still be validated. */
    @Test
    public void valuesFollowedByBgp_unknownClassDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { VALUES ?x { \"a\" } . ?s a cim:DoesNotExist . }");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    // ============================================================================================
    // SERVICE block (OpService) — must NOT recurse into the remote endpoint body
    // ============================================================================================

    /** Unknown class inside SERVICE body must NOT produce a validation error. */
    @Test
    public void serviceBlock_unknownClassInsideIsIgnored() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { SERVICE <urn:endpoint> { ?s a cim:DoesNotExist . } }");
        long errors = r.annotations().stream()
                .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
                .count();
        assertEquals("SERVICE body must not be validated (remote schema is out of scope)", 0, errors);
    }

    /** Unknown class outside SERVICE must still fire; class inside SERVICE must be ignored. */
    @Test
    public void serviceBlock_outsideTermsStillValidated() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { "
                + "  SERVICE <urn:ep> { ?x cim:ACLineSegment.r ?r } . "
                + "  ?s a cim:DoesNotExistOutside . "
                + "}");
        assertHasUnknownClass(r, CIM + "DoesNotExistOutside");
        // Nothing inside SERVICE should add a second error.
        long totalErrors = r.annotations().stream()
                .filter(a -> a.severity() == SparqlValidationSeverity.ERROR)
                .count();
        assertEquals("exactly one error: the term outside SERVICE only", 1, totalErrors);
    }

    // ============================================================================================
    // Negative patterns — body must still validate unknown terms (isolation is for type inference)
    // ============================================================================================

    /** FILTER NOT EXISTS body: unknown property is still reported as an error. */
    @Test
    public void filterNotExists_bodyStillValidatesUnknownProperty() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s a cim:ACLineSegment . "
                + "  FILTER NOT EXISTS { ?s cim:doesNotExist ?x } "
                + "}");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** MINUS body: unknown property is still reported as an error. */
    @Test
    public void minusBody_stillValidatesUnknownProperty() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT * WHERE { "
                + "  ?s a cim:ACLineSegment . "
                + "  MINUS { ?s cim:doesNotExist ?x } "
                + "}");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    // ============================================================================================
    // Multi-segment SELECT (;-separated) — regression for split-and-validate logic
    // ============================================================================================

    /** Two SELECT queries separated by {@code ;}: unknown property in second segment detected. */
    @Test
    public void multiSelectSemicolon_unknownPropertyInSecondSegmentDetected() {
        // The splitter recognises a line whose entire trimmed content is "}" followed by ";"
        // on the next line, or "};" on a single line.
        var r = api.validateSparql(PREAMBLE
                + "SELECT ?s WHERE {\n"
                + "  ?s a cim:ACLineSegment\n"
                + "};\n"
                + "SELECT ?x WHERE { ?x cim:doesNotExist ?v }");
        assertHasUnknownProperty(r, CIM + "doesNotExist");
    }

    /** Two SELECT queries separated by {@code ;}: unknown class in first segment detected. */
    @Test
    public void multiSelectSemicolon_unknownClassInFirstSegmentDetected() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT ?s WHERE {\n"
                + "  ?s a cim:DoesNotExist\n"
                + "};\n"
                + "SELECT ?x WHERE { ?x cim:ACLineSegment.r ?v }");
        assertHasUnknownClass(r, CIM + "DoesNotExist");
    }

    /** Two valid SELECT queries separated by {@code ;}: no errors. */
    @Test
    public void multiSelectSemicolon_bothValid_noErrors() {
        var r = api.validateSparql(PREAMBLE
                + "SELECT ?s WHERE {\n"
                + "  ?s a cim:ACLineSegment\n"
                + "};\n"
                + "SELECT ?s WHERE { ?s cim:ACLineSegment.r ?r }");
        assertNoErrors(r);
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private static void assertNoErrors(SparqlValidationResult r) {
        var errors = r.annotations().stream()
                .filter(a -> a.severity() == SparqlValidationSeverity.ERROR)
                .toList();
        assertTrue("expected no ERROR annotations, got: " + errors, errors.isEmpty());
    }

    private static void assertHasUnknownClass(SparqlValidationResult r, String classUri) {
        boolean found = r.annotations().stream()
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS
                        && a.term() != null
                        && classUri.equals(a.term().getURI()));
        assertTrue("expected UNKNOWN_CLASS for <" + classUri + ">, got: " + r.annotations(), found);
    }

    private static void assertHasUnknownProperty(SparqlValidationResult r, String propUri) {
        boolean found = r.annotations().stream()
                .anyMatch(a -> a.code() == SparqlValidationCode.UNKNOWN_PROPERTY
                        && a.term() != null
                        && propUri.equals(a.term().getURI()));
        assertTrue("expected UNKNOWN_PROPERTY for <" + propUri + ">, got: " + r.annotations(), found);
    }
}
