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

package de.soptim.opencgmes.cimreader.core.loader;

import static de.soptim.opencgmes.cimreader.core.TestModelBuilder.differenceModel;
import static de.soptim.opencgmes.cimreader.core.TestModelBuilder.fullModel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimreader.core.TestModelBuilder;
import de.soptim.opencgmes.cimreader.core.source.ModelSource;
import de.soptim.opencgmes.cimxml.graph.FastDeltaGraph;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.Test;

public class LoadStrategyTest {

  private static final String NAME = "IdentifiedObject.name";
  private static final Node NAME_PREDICATE =
      NodeFactory.createURI("http://iec.ch/TC57/CIM100#" + NAME);

  private static final String F1 = "urn:uuid:f1";
  private static final String F2 = "urn:uuid:f2";
  private static final String F3 = "urn:uuid:f3";
  private static final String FX = "urn:uuid:fx";
  private static final String D1 = "urn:uuid:d1";
  private static final String MISSING = "urn:uuid:missing";

  // ---------- reference selection ----------

  @Test
  public void firstStrategy_storesFirstGraphAsFullGraph() throws IOException {
    var dataset = new FirstGraphLoadStrategy().load(List.of(similarSequence()));

    assertFull(dataset, F1);
    assertDelta(dataset, F2);
    assertDelta(dataset, F3);
  }

  @Test
  public void lastStrategy_storesLastGraphAsFullGraph() throws IOException {
    var dataset = new LastGraphLoadStrategy().load(List.of(similarSequence()));

    assertDelta(dataset, F1);
    assertDelta(dataset, F2);
    assertFull(dataset, F3);
  }

  @Test
  public void middleStrategy_storesMiddleGraphAsFullGraph() throws IOException {
    var dataset = new MiddleGraphLoadStrategy().load(List.of(similarSequence()));

    assertDelta(dataset, F1);
    assertFull(dataset, F2);
    assertDelta(dataset, F3);
  }

  @Test
  public void randomStrategy_storesRandomlySelectedGraphAsFullGraph() throws IOException {
    var dataset = new RandomGraphLoadStrategy(fixedRandom(2)).load(List.of(similarSequence()));

    assertDelta(dataset, F1);
    assertDelta(dataset, F2);
    assertFull(dataset, F3);
  }

  @Test
  public void randomStrategy_usesGivenRandom() throws IOException {
    long seed = 42;
    var expectedIndex = new Random(seed).nextInt(3);
    var iris = List.of(F1, F2, F3);

    var dataset = new RandomGraphLoadStrategy(new Random(seed))
        .load(List.of(similarSequence()));

    for (int i = 0; i < iris.size(); i++) {
      if (i == expectedIndex) {
        assertFull(dataset, iris.get(i));
      } else {
        assertDelta(dataset, iris.get(i));
      }
    }
  }

  // ---------- content ----------

  @Test
  public void allStrategies_produceSameGraphContent() throws IOException {
    for (var strategy : allStrategies()) {
      var dataset = strategy.load(List.of(similarSequence()));

      var name = strategy.getClass().getSimpleName();
      assertEquals(name, names(0, 10), names(graph(dataset, F1)));
      assertEquals(name, replace(names(0, 10), "n0", "c0"), names(graph(dataset, F2)));
      assertEquals(name, replace(replace(names(0, 10), "n0", "c0"), "n1", "c1"),
          names(graph(dataset, F3)));
    }
  }

  @Test
  public void largeChange_isStoredAsFullGraph() throws IOException {
    var sources = List.of(
        tenElements(F1, UnaryOperator.identity()).toModelSource(),
        completelyDifferent(FX).toModelSource());

    var dataset = new FirstGraphLoadStrategy().load(List.of(sources));

    assertFull(dataset, F1);
    assertFull(dataset, FX);
    assertEquals(names("x", 0, 10), names(graph(dataset, FX)));
  }

  @Test
  public void differenceModel_isResolvedAgainstPredecessor() throws IOException {
    var d1 = differenceModel(D1).supersedes(F1)
        .add("e10", "ACLineSegment", NAME, "n10")
        .remove("e0", "ACLineSegment", NAME, "n0")
        .toModelSource();
    var sources = List.of(tenElements(F1, UnaryOperator.identity()).toModelSource(), d1);

    for (var strategy : allStrategies()) {
      var dataset = strategy.load(List.of(sources));

      assertEquals(strategy.getClass().getSimpleName(), names(1, 11),
          names(graph(dataset, D1)));
    }
  }

  @Test
  public void differenceModel_isResolvedAcrossHeaderGroups() throws IOException {
    var d1 = differenceModel(D1).supersedes(F1)
        .add("e10", "ACLineSegment", NAME, "n10")
        .toModelSource();
    var f1 = tenElements(F1, UnaryOperator.identity()).toModelSource();

    var dataset = new FirstGraphLoadStrategy().load(List.of(List.of(d1), List.of(f1)));

    assertEquals(names(0, 11), names(graph(dataset, D1)));
    assertEquals(names(0, 10), names(graph(dataset, F1)));
  }

  @Test
  public void differenceModelWithoutPredecessor_throws() {
    var d1 = differenceModel(D1).supersedes(MISSING)
        .add("e10", "ACLineSegment", NAME, "n10")
        .toModelSource();

    for (var strategy : allStrategies()) {
      var e = assertThrows(IllegalStateException.class,
          () -> strategy.load(List.of(List.of(d1))));
      assertTrue(e.getMessage().contains(D1));
    }
  }

  @Test
  public void emptySourceGroups_produceEmptyDataset() throws IOException {
    for (var strategy : allStrategies()) {
      var dataset = strategy.load(List.of(List.of(), List.of()));

      assertFalse(dataset.listGraphNodes().hasNext());
    }
  }

  @Test
  public void multipleGroups_areAllLoaded() throws IOException {
    var groupA = List.of(tenElements(F1, UnaryOperator.identity()).toModelSource());
    var groupB = List.of(completelyDifferent(FX).toModelSource());

    var dataset = new MiddleGraphLoadStrategy().load(List.of(groupA, groupB));

    assertEquals(names(0, 10), names(graph(dataset, F1)));
    assertEquals(names("x", 0, 10), names(graph(dataset, FX)));
  }

  // ---------- helpers ----------

  private static List<LoadStrategy> allStrategies() {
    return List.of(new FirstGraphLoadStrategy(), new LastGraphLoadStrategy(),
        new MiddleGraphLoadStrategy(), new RandomGraphLoadStrategy(new Random(7)));
  }

  /**
   * Three full models where each differs only slightly from its neighbour.
   */
  private static List<ModelSource> similarSequence() {
    return List.of(
        tenElements(F1, UnaryOperator.identity()).toModelSource(),
        tenElements(F2, n -> n.equals("n0") ? "c0" : n).toModelSource(),
        tenElements(F3, n -> n.equals("n0") ? "c0" : n.equals("n1") ? "c1" : n)
            .toModelSource());
  }

  private static TestModelBuilder tenElements(String iri, UnaryOperator<String> rename) {
    var builder = fullModel(iri);
    for (int i = 0; i < 10; i++) {
      builder.element("e" + i, "ACLineSegment", NAME, rename.apply("n" + i));
    }
    return builder;
  }

  private static TestModelBuilder completelyDifferent(String iri) {
    var builder = fullModel(iri);
    for (int i = 0; i < 10; i++) {
      builder.element("x" + i, "Breaker", NAME, "x" + i);
    }
    return builder;
  }

  private static Random fixedRandom(int value) {
    return new Random() {
      @Override
      public int nextInt(int bound) {
        return value;
      }
    };
  }

  private static Graph graph(DatasetGraph dataset, String iri) {
    var graph = dataset.getGraph(NodeFactory.createURI(iri));
    assertNotNull("Missing graph " + iri, graph);
    return graph;
  }

  private static void assertFull(DatasetGraph dataset, String iri) {
    assertFalse(iri + " should be a full graph", graph(dataset, iri) instanceof FastDeltaGraph);
  }

  private static void assertDelta(DatasetGraph dataset, String iri) {
    assertTrue(iri + " should be a delta graph", graph(dataset, iri) instanceof FastDeltaGraph);
  }

  private static Set<String> names(Graph graph) {
    Set<String> names = new HashSet<>();
    graph.find(Node.ANY, NAME_PREDICATE, Node.ANY)
        .forEach(t -> names.add(t.getObject().getLiteralLexicalForm()));
    return names;
  }

  private static Set<String> names(int from, int to) {
    return names("n", from, to);
  }

  private static Set<String> names(String prefix, int from, int to) {
    Set<String> names = new HashSet<>();
    for (int i = from; i < to; i++) {
      names.add(prefix + i);
    }
    return names;
  }

  private static Set<String> replace(Set<String> names, String from, String to) {
    var copy = new HashSet<>(names);
    copy.remove(from);
    copy.add(to);
    return copy;
  }
}