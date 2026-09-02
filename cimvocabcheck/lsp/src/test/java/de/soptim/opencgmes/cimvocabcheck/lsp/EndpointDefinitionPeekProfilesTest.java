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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.system.Txn;
import org.eclipse.lsp4j.Location;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Covers the per-profile "peek" a term hosted on a SPARQL endpoint resolves to: a term declared in
 * several profiles yields one document per profile, scoped to that profile's graph, in the order
 * the profiles were given. The fetches run together rather than one after another, so this also
 * pins that concurrency down to what it must not change — the number of results, their order, and
 * the fall-back to the unscoped peek when the graphs yield nothing.
 */
public class EndpointDefinitionPeekProfilesTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
  private static final String TERM = CIM + "ACLineSegment";
  private static final String EQ_GRAPH = "http://example.org/graphs/EquipmentProfile";
  private static final String SSH_GRAPH = "http://example.org/graphs/SteadyStateHypothesisProfile";

  private static FusekiServer fuseki;
  private static String endpoint;

  @BeforeClass
  public static void startFuseki() {
    DatasetGraph dsg = DatasetGraphFactory.createTxnMem();
    Txn.executeWrite(
        dsg,
        () -> {
          // The same term in two graphs, each saying something different about it — which is the
          // whole reason the peek is per profile rather than one merged document.
          dsg.add(
              NodeFactory.createURI(EQ_GRAPH),
              NodeFactory.createURI(TERM),
              NodeFactory.createURI(RDFS + "comment"),
              NodeFactory.createLiteralString("as Equipment declares it"));
          dsg.add(
              NodeFactory.createURI(SSH_GRAPH),
              NodeFactory.createURI(TERM),
              NodeFactory.createURI(RDFS + "comment"),
              NodeFactory.createLiteralString("as Steady State Hypothesis declares it"));
        });
    fuseki = FusekiServer.create().port(0).add("/ds", dsg).build().start();
    endpoint = fuseki.serverURL() + "ds";
  }

  @AfterClass
  public static void stopFuseki() {
    fuseki.stop();
  }

  private static EndpointDefinitionPeek peek() {
    return new EndpointDefinitionPeek(Duration.ofSeconds(10));
  }

  private static List<EndpointDefinitionPeek.ProfileGraph> bothProfiles() {
    return List.of(
        new EndpointDefinitionPeek.ProfileGraph("EQ/3.0", EQ_GRAPH),
        new EndpointDefinitionPeek.ProfileGraph("SSH/3.0", SSH_GRAPH));
  }

  @Test
  public void oneDocumentPerProfile_inTheOrderTheProfilesWereGiven() throws Exception {
    EndpointDefinitionPeek peek = peek();
    try {
      List<Location> locations = peek.locationsFor(endpoint, TERM, bothProfiles());

      assertEquals("one location per declaring profile", 2, locations.size());
      String first = Path.of(java.net.URI.create(locations.get(0).getUri())).toString();
      String second = Path.of(java.net.URI.create(locations.get(1).getUri())).toString();
      assertNotEquals("the two profiles must not share one document", first, second);
      assertTrue("first document is the first profile's: " + first, first.contains("EQ-3.0"));
      assertTrue("second document is the second profile's: " + second, second.contains("SSH-3.0"));

      String eq = Files.readString(Path.of(java.net.URI.create(locations.get(0).getUri())));
      assertTrue(
          "each document holds only its own profile's triples: " + eq,
          eq.contains("as Equipment declares it") && !eq.contains("Steady State"));
    } finally {
      peek.shutdown();
    }
  }

  @Test
  public void fallsBackToTheUnscopedPeekWhenNoProfileGraphHoldsTheTerm() throws Exception {
    EndpointDefinitionPeek peek = peek();
    try {
      // A stale index: the profiles name graphs the endpoint does not have the term in.
      List<Location> locations =
          peek.locationsFor(
              endpoint,
              TERM,
              List.of(
                  new EndpointDefinitionPeek.ProfileGraph("EQ/3.0", "http://example.org/gone")));

      assertEquals("the merged peek is still better than nothing", 1, locations.size());
      String merged = Files.readString(Path.of(java.net.URI.create(locations.get(0).getUri())));
      assertTrue(
          "the merged document spans every graph: " + merged,
          merged.contains("as Equipment declares it") && merged.contains("Steady State"));
    } finally {
      peek.shutdown();
    }
  }

  @Test
  public void noProfilesMeansTheMergedPeek() throws Exception {
    EndpointDefinitionPeek peek = peek();
    try {
      assertEquals(1, peek.locationsFor(endpoint, TERM, List.of()).size());
      assertEquals(1, peek.locationsFor(endpoint, TERM, null).size());
      assertTrue(peek.locationsFor(endpoint, CIM + "NotThere", null).isEmpty());
    } finally {
      peek.shutdown();
    }
  }
}
