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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import java.util.List;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.Test;

/**
 * Reproduces the bug where the same shape-structure violation, reported twice for two distinct
 * shapes referencing the same offending term, is squiggled at the same (first-occurrence) position
 * for both — instead of each diagnostic pointing at its own shape's declaration.
 */
public class ShaclDuplicateViolationPositionTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";

  @Test
  public void duplicateTargetClassViolations_getDistinctPositions() {
    String turtle =
        "@prefix sh:  <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix cim: <"
            + CIM
            + "> .\n"
            + "@prefix ex:  <http://example.org/shapes#> .\n"
            + "\n"
            + "ex:ShapeOne\n"
            + "    a sh:NodeShape ;\n"
            + "    sh:targetClass cim:DoesNotExist .\n"
            + "\n"
            + "ex:ShapeTwo\n"
            + "    a sh:NodeShape ;\n"
            + "    sh:targetClass cim:DoesNotExist .\n";

    Model model = ModelFactory.createDefaultModel();
    RDFParser.fromString(turtle, Lang.TURTLE).parse(model);

    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(
                "http://example.org/profile/1.0", List.of(CIM + "SomeOtherClass"), List.of())
            .build();
    SparqlValidationApi api = new SparqlValidationApi(index);
    ShaclValidationResult result = api.validateShacl(model.getGraph());

    List<SparqlValidationAnnotation> unknownClass =
        result.shapeAnnotations().stream()
            .filter(a -> a.code() == SparqlValidationCode.UNKNOWN_CLASS)
            .toList();
    assertEquals("both ex:ShapeOne and ex:ShapeTwo must be flagged", 2, unknownClass.size());

    List<Diagnostic> diagnostics =
        unknownClass.stream()
            .map(a -> SparqlTextDocumentService.convertShapeAnnotation(a, turtle, model))
            .toList();

    int line1 = diagnostics.get(0).getRange().getStart().getLine();
    int line2 = diagnostics.get(1).getRange().getStart().getLine();
    assertNotEquals(
        "the two duplicate violations must be squiggled at their own shape's line, not both"
            + " collapsed onto the first occurrence",
        line1,
        line2);
    // ex:ShapeOne's sh:targetClass is on (0-based) line 6, ex:ShapeTwo's on line 10.
    assertEquals(Set.of(6, 10), Set.of(line1, line2));
  }
}
