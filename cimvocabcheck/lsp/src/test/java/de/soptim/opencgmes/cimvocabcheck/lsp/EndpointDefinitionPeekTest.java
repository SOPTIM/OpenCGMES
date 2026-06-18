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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.Test;

/**
 * Covers the schema-independent rendering/locating helpers of {@link EndpointDefinitionPeek} (the
 * network fetch itself is exercised live against a Fuseki endpoint).
 */
public class EndpointDefinitionPeekTest {

  private static final String SWITCH_OPEN = "http://iec.ch/TC57/CIM100#Switch.open";

  private static Graph termGraph() {
    String ttl =
        """
        @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix cim:  <http://iec.ch/TC57/CIM100#> .
        @prefix cims: <http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#> .

        cim:Switch.open a rdf:Property ;
            rdfs:label "open" ;
            rdfs:comment "The attribute tells if the switch is considered open." ;
            rdfs:domain cim:Switch ;
            cims:dataType cim:Boolean .
        """;
    var m = ModelFactory.createDefaultModel();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(m);
    return m.getGraph();
  }

  @Test
  public void rendersTurtleWithStandardPrefixesAndTheTerm() {
    String ttl = EndpointDefinitionPeek.renderTurtle(termGraph(), SWITCH_OPEN);
    assertTrue("cim prefix declared", ttl.contains("@prefix cim:") || ttl.contains("PREFIX cim:"));
    assertTrue("the term appears", ttl.contains("Switch.open"));
    assertTrue("its comment appears", ttl.contains("considered open"));
  }

  @Test
  public void subjectLineLandsOnTheTerm() {
    String ttl = EndpointDefinitionPeek.renderTurtle(termGraph(), SWITCH_OPEN);
    int line = EndpointDefinitionPeek.subjectLine(ttl, SWITCH_OPEN);
    String[] lines = ttl.split("\n", -1);
    assertTrue("located a real line", line >= 0 && line < lines.length);
    assertTrue(
        "the located line names the term: <" + lines[line] + ">",
        lines[line].contains("Switch.open"));
  }

  @Test
  public void namespaceAndLocalNameSplitOnHash() {
    assertEquals("http://iec.ch/TC57/CIM100#", EndpointDefinitionPeek.namespaceOf(SWITCH_OPEN));
    assertEquals("Switch.open", EndpointDefinitionPeek.localName(SWITCH_OPEN));
  }

  @Test
  public void remoteOnly_localOrNullEndpointYieldsNoPeek() {
    var peek = new EndpointDefinitionPeek(java.time.Duration.ofSeconds(1));
    assertTrue(peek.locationFor(null, SWITCH_OPEN).isEmpty());
    assertTrue(peek.locationFor("./local-file.ttl", SWITCH_OPEN).isEmpty());
  }
}
