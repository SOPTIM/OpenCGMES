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

package de.soptim.opencgmes.cimvocabcheck.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.util.List;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.Test;

/**
 * Covers {@link CgmesSchemaLoader#indexFromGraphs} — building a schema index from in-memory graphs,
 * as used when loading a CGMES schema from a SPARQL endpoint's named graphs.
 */
public class IndexFromGraphsTest {

  private static final String CIM100 = "http://iec.ch/TC57/CIM100#";

  /**
   * A minimal CIM 17 profile graph WITHOUT a {@code cim} namespace prefix — exactly what a graph
   * fetched over SPARQL looks like. Profile detection is prefix-based, so this only wraps if {@code
   * indexFromGraphs} re-asserts the prefix from the CIM100 IRIs.
   */
  private static Graph profileGraphWithoutCimPrefix() {
    String ttl =
        """
        @prefix owl:  <http://www.w3.org/2002/07/owl#> .
        @prefix dcat: <http://www.w3.org/ns/dcat#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

        <http://iec.ch/TC57/CIM100/EquipmentCore> a owl:Ontology ;
            owl:versionIRI <http://iec.ch/TC57/CIM100/EquipmentCore/1.0> ;
            dcat:keyword "EQ" .

        <http://iec.ch/TC57/CIM100#ACLineSegment> a rdfs:Class .
        """;
    Model m = ModelFactory.createDefaultModel();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(m);
    Graph g = m.getGraph();
    // Precondition: the graph genuinely lacks a cim prefix (the situation we must recover from).
    assertTrue(
        "test graph must not declare a cim prefix",
        g.getPrefixMapping().getNsPrefixURI("cim") == null);
    return g;
  }

  private static Graph instanceDataGraph() {
    String ttl =
        """
        <http://example.org/inst1> a <http://iec.ch/TC57/CIM100#ACLineSegment> .
        <http://example.org/inst1> <http://iec.ch/TC57/CIM100#IdentifiedObject.name> "L1" .
        """;
    Model m = ModelFactory.createDefaultModel();
    RDFParser.fromString(ttl, Lang.TURTLE).parse(m);
    return m.getGraph();
  }

  @Test
  public void buildsIndexFromPrefixlessProfileGraph() throws Exception {
    RdfsSchemaIndex index =
        CgmesSchemaLoader.indexFromGraphs(List.of(profileGraphWithoutCimPrefix()));

    assertFalse(
        "ACLineSegment class should be in the index",
        index.findClass(NodeFactory.createURI(CIM100 + "ACLineSegment")).isEmpty());
  }

  @Test
  public void skipsNonProfileGraphsButLoadsProfiles() throws Exception {
    // A mix of instance data (not a profile) and a real profile graph.
    RdfsSchemaIndex index =
        CgmesSchemaLoader.indexFromGraphs(
            List.of(instanceDataGraph(), profileGraphWithoutCimPrefix()));

    assertFalse(index.findClass(NodeFactory.createURI(CIM100 + "ACLineSegment")).isEmpty());
  }

  @Test
  public void failsWhenNoGraphIsAProfile() {
    try {
      CgmesSchemaLoader.indexFromGraphs(List.of(instanceDataGraph()));
      fail("expected SchemaLoadException when no profile graph is present");
    } catch (CgmesSchemaLoader.SchemaLoadException expected) {
      // success
    }
  }

  @Test
  public void failsOnEmptyInput() {
    try {
      CgmesSchemaLoader.indexFromGraphs(List.of());
      fail("expected SchemaLoadException for empty graph list");
    } catch (CgmesSchemaLoader.SchemaLoadException expected) {
      // success
    }
  }
}
