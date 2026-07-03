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

package de.soptim.opencgmes.cimvocabcheck.core;

import static org.junit.Assert.assertEquals;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.EmbeddedSourceMapper;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.EmbeddedSparql;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.Shacl;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.Test;

/**
 * Tests mapping of embedded-SPARQL annotation positions (in the rendered query) back to Turtle
 * source positions. This is the shared mechanism used by both the CLI and the LSP.
 */
public class EmbeddedSourceMapperTest {

  // ---- exact substring strategy -----------------------------------------------------------

  private static final String RAW = "\nSELECT ?x WHERE {\n  ?x a ?y .\n}\n";

  // Turtle lines (0-based): 0 @prefix, 1 blank, 2 `ex:S ex:q """`, 3 SELECT, 4 `  ?x a ?y .`,
  // 5 `}`, 6 `""" .`
  private static final String TURTLE =
      "@prefix ex: <http://example.org/> .\n\nex:S ex:q \"\"\"" + RAW + "\"\"\" .\n";

  private static EmbeddedSparql embedded(String raw, Map<String, String> prefixes) {
    return new EmbeddedSparql(
        NodeFactory.createBlankNode(),
        Shacl.SELECT,
        EmbeddedSparql.Kind.SELECT,
        raw,
        prefixes,
        Set.of(),
        Set.of());
  }

  @Test
  public void mapsRawQueryLineToTurtleLine() {
    EmbeddedSparql e = embedded(RAW, Map.of("ex", "http://example.org/"));
    // rendered line 3 (1-based) = 1 PREFIX line + rawQuery line 1 ("SELECT ?x WHERE {").
    int[] pos = EmbeddedSourceMapper.toTurtlePosition(3, 3, e, TURTLE);
    assertEquals("turtle line", 3, pos[0]);
    assertEquals("column carried through", 2, pos[1]);
  }

  @Test
  public void mapsDeeperRawQueryLine() {
    EmbeddedSparql e = embedded(RAW, Map.of("ex", "http://example.org/"));
    // rendered line 4 = rawQuery line 2 ("  ?x a ?y .").
    int[] pos = EmbeddedSourceMapper.toTurtlePosition(4, 3, e, TURTLE);
    assertEquals(4, pos[0]);
    assertEquals(2, pos[1]);
  }

  @Test
  public void positionInsidePrefixBlockClampsToRawQueryStart() {
    EmbeddedSparql e = embedded(RAW, Map.of("ex", "http://example.org/"));
    // rendered line 1 is the injected PREFIX line — clamps to the first raw-query line.
    int[] pos = EmbeddedSourceMapper.toTurtlePosition(1, 5, e, TURTLE);
    assertEquals(2, pos[0]);
    assertEquals(0, pos[1]);
  }

  // ---- line-anchor fallback strategy ------------------------------------------------------

  @Test
  public void fallsBackToLineAnchorWhenRawQueryNotSubstring() {
    // The raw query is NOT an exact substring of the Turtle (the second line differs), so the
    // mapper must fall back to anchoring on the first non-blank raw-query line.
    String raw = "\nSELECT ?zzz WHERE {\n  DIFFERENT ?zzz\n}\n";
    String turtle =
        "line0\n"
            + "line1\n"
            + "        SELECT ?zzz WHERE {\n"
            + "            stuff\n"
            + "        }\n";
    EmbeddedSparql e = embedded(raw, Map.of());
    // prefixes empty; rendered line 3 (1-based) = rawQuery line 2 ("  DIFFERENT ?zzz").
    int[] pos = EmbeddedSourceMapper.toTurtlePosition(3, 1, e, turtle);
    assertEquals(3, pos[0]);
  }

  // ---- end-to-end: a broken embedded SELECT maps to the right Turtle line -----------------

  @Test
  public void brokenEmbeddedSelectMapsToItsTurtleLine() {
    String turtle =
        "@prefix sh:  <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix cim: <http://iec.ch/TC57/CIM100#> .\n"
            + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
            + "@prefix ex:  <http://example.org/shapes#> .\n"
            + "\n"
            + "ex: sh:declare [ sh:prefix \"cim\" ;\n"
            + "                 sh:namespace \"http://iec.ch/TC57/CIM100#\"^^xsd:anyURI ] .\n"
            + "\n"
            + "ex:Shape a sh:NodeShape ;\n"
            + "    sh:targetClass cim:ACLineSegment ;\n"
            + "    sh:sparql [\n"
            + "        a sh:SPARQLConstraint ;\n"
            + "        sh:prefixes ex: ;\n"
            + "        sh:select \"\"\"\n"
            + "            SELEEECT $this WHERE {\n" // turtle line 14 (0-based)
            + "                $this cim:ACLineSegment.r ?r .\n"
            + "            }\n"
            + "        \"\"\" ;\n"
            + "    ] .\n";

    Graph g = GraphFactory.createDefaultGraph();
    RDFParser.fromString(turtle, Lang.TURTLE).parse(g);
    var api = new SparqlValidationApi(RdfsSchemaIndex.builder().build());
    ShaclValidationResult result = api.validateShacl(g);

    int mappedLine = -1;
    for (var er : result.embeddedResults()) {
      for (var a : er.result().annotations()) {
        if (a.term() == null) { // the syntax error
          mappedLine = EmbeddedSourceMapper.toTurtlePosition(a, er.embedded(), turtle)[0];
        }
      }
    }
    assertEquals("SELEEECT must map to its own Turtle line, not line 0/1", 14, mappedLine);
  }
}
