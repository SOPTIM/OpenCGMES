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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StrictnessLevel}: parse, apply (filter/promote), and
 * {@link SparqlValidationResult#isValid(StrictnessLevel)}.
 */
public class StrictnessLevelTest {

    // ---- parse -----------------------------------------------------------------------------

    @Test
    public void parse_caseInsensitive() {
        assertEquals(StrictnessLevel.STRICT,     StrictnessLevel.parse("STRICT"));
        assertEquals(StrictnessLevel.STRICT,     StrictnessLevel.parse("strict"));
        assertEquals(StrictnessLevel.PEDANTIC,   StrictnessLevel.parse("Pedantic"));
        assertEquals(StrictnessLevel.PERMISSIVE, StrictnessLevel.parse("permissive"));
        assertEquals(StrictnessLevel.DEFAULT,    StrictnessLevel.parse("default"));
    }

    @Test
    public void parse_nullOrBlankReturnsDefault() {
        assertEquals(StrictnessLevel.DEFAULT, StrictnessLevel.parse(null));
        assertEquals(StrictnessLevel.DEFAULT, StrictnessLevel.parse(""));
        assertEquals(StrictnessLevel.DEFAULT, StrictnessLevel.parse("  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parse_unknownThrows() {
        StrictnessLevel.parse("ultra");
    }

    // ---- DEFAULT — no change ---------------------------------------------------------------

    @Test
    public void default_preservesAllAnnotations() {
        var ann = List.of(
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.UNKNOWN_CLASS),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.DATATYPE_MISMATCH),
                annotation(SparqlValidationSeverity.INFO,  SparqlValidationCode.QUERY_IMPLIED_TYPE));

        var out = StrictnessLevel.DEFAULT.apply(ann);
        assertEquals(3, out.size());
        assertEquals(SparqlValidationSeverity.ERROR, out.get(0).severity());
        assertEquals(SparqlValidationSeverity.WARN,  out.get(1).severity());
        assertEquals(SparqlValidationSeverity.INFO,  out.get(2).severity());
    }

    // ---- STRICT — WARN → ERROR -------------------------------------------------------------

    @Test
    public void strict_promotesWarnToError() {
        var ann = List.of(
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.UNKNOWN_CLASS),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.DATATYPE_MISMATCH),
                annotation(SparqlValidationSeverity.INFO,  SparqlValidationCode.QUERY_IMPLIED_TYPE));

        var out = StrictnessLevel.STRICT.apply(ann);
        assertEquals(3, out.size());
        assertEquals(SparqlValidationSeverity.ERROR, out.get(0).severity()); // ERROR stays ERROR
        assertEquals(SparqlValidationSeverity.ERROR, out.get(1).severity()); // WARN → ERROR
        assertEquals(SparqlValidationSeverity.INFO,  out.get(2).severity()); // INFO unchanged
    }

    @Test
    public void strict_codePreservedAfterPromotion() {
        var warn = annotation(SparqlValidationSeverity.WARN, SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE);
        var out  = StrictnessLevel.STRICT.apply(List.of(warn));
        assertEquals(SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE, out.get(0).code());
    }

    // ---- PEDANTIC — WARN + INFO → ERROR ---------------------------------------------------

    @Test
    public void pedantic_promotesWarnAndInfoToError() {
        var ann = List.of(
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.SYNTAX_ERROR),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.GRAPH_NOT_CONFIGURED),
                annotation(SparqlValidationSeverity.INFO,  SparqlValidationCode.QUERY_IMPLIED_TYPE));

        var out = StrictnessLevel.PEDANTIC.apply(ann);
        assertEquals(3, out.size());
        assertTrue("all should be ERROR", out.stream()
                .allMatch(a -> a.severity() == SparqlValidationSeverity.ERROR));
    }

    // ---- PERMISSIVE — only structural codes -----------------------------------------------

    @Test
    public void permissive_keepsStructuralCodesOnly() {
        var ann = List.of(
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.UNKNOWN_CLASS),
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.UNKNOWN_PROPERTY),
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.SYNTAX_ERROR),
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.INVALID_CARDINALITY),
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.DATATYPE_MISMATCH),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.NODE_KIND_INCOMPATIBLE_WITH_RANGE),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.GRAPH_NOT_CONFIGURED),
                annotation(SparqlValidationSeverity.WARN,  SparqlValidationCode.UNSUPPORTED_DYNAMIC_PROPERTY),
                annotation(SparqlValidationSeverity.INFO,  SparqlValidationCode.QUERY_IMPLIED_TYPE));

        var out = StrictnessLevel.PERMISSIVE.apply(ann);
        assertEquals("only 4 structural codes kept", 4, out.size());
        assertTrue(out.stream().allMatch(a -> isStructural(a.code())));
    }

    @Test
    public void permissive_emptyInputGivesEmptyOutput() {
        assertTrue(StrictnessLevel.PERMISSIVE.apply(List.of()).isEmpty());
    }

    // ---- SparqlValidationResult.isValid(StrictnessLevel) ----------------------------------

    @Test
    public void result_isValid_defaultEquivalentToNoArg() {
        var result = resultWith(
                annotation(SparqlValidationSeverity.WARN, SparqlValidationCode.DATATYPE_MISMATCH));
        assertTrue("WARN alone → valid under DEFAULT", result.isValid());
        assertTrue("WARN alone → valid under DEFAULT (overload)", result.isValid(StrictnessLevel.DEFAULT));
    }

    @Test
    public void result_isValid_strictFailsOnWarn() {
        var result = resultWith(
                annotation(SparqlValidationSeverity.WARN, SparqlValidationCode.DATATYPE_MISMATCH));
        assertTrue("WARN → valid under DEFAULT",   result.isValid(StrictnessLevel.DEFAULT));
        assertFalse("WARN → invalid under STRICT", result.isValid(StrictnessLevel.STRICT));
    }

    @Test
    public void result_isValid_pedanticFailsOnInfo() {
        var result = resultWith(
                annotation(SparqlValidationSeverity.INFO, SparqlValidationCode.QUERY_IMPLIED_TYPE));
        assertTrue("INFO → valid under DEFAULT",    result.isValid(StrictnessLevel.DEFAULT));
        assertTrue("INFO → valid under STRICT",     result.isValid(StrictnessLevel.STRICT));
        assertFalse("INFO → invalid under PEDANTIC", result.isValid(StrictnessLevel.PEDANTIC));
    }

    @Test
    public void result_isValid_permissiveIgnoresSemanticErrors() {
        var result = resultWith(
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.PROPERTY_NOT_ALLOWED_FOR_CLASS));
        assertFalse("semantic ERROR → invalid under DEFAULT",    result.isValid(StrictnessLevel.DEFAULT));
        assertTrue("semantic ERROR filtered out by PERMISSIVE", result.isValid(StrictnessLevel.PERMISSIVE));
    }

    @Test
    public void result_isValid_permissiveStillFailsOnUnknownClass() {
        var result = resultWith(
                annotation(SparqlValidationSeverity.ERROR, SparqlValidationCode.UNKNOWN_CLASS));
        assertFalse("UNKNOWN_CLASS is structural → invalid even under PERMISSIVE",
                result.isValid(StrictnessLevel.PERMISSIVE));
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private static SparqlValidationAnnotation annotation(
            SparqlValidationSeverity severity, SparqlValidationCode code) {
        return new SparqlValidationAnnotation(
                severity, null, null, "test message", code,
                null, List.of(), List.of(), null);
    }

    private static SparqlValidationResult resultWith(SparqlValidationAnnotation... annotations) {
        return new SparqlValidationResult("SELECT *", null, List.of(annotations));
    }

    private static boolean isStructural(SparqlValidationCode code) {
        return switch (code) {
            case SYNTAX_ERROR, UNKNOWN_CLASS, UNKNOWN_PROPERTY, INVALID_CARDINALITY -> true;
            default -> false;
        };
    }
}
