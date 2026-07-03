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

package de.soptim.opencgmes.cimxml.graph;

import java.util.Iterator;
import java.util.stream.Stream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NullIterator;

/**
 * Extends the Apache Jena MultiUnion but {@link #find } and {@link #stream } do not eliminate
 * duplicates.
 * This implementation is based on
 * <a
 * href="https://github.com/apache/jena/blob/master/jena-core/src/main/java/org/apache/jena/graph/compose/MultiUnion.java"
 * />.
 */
public class DisjointMultiUnion extends MultiUnion {

  /**
   * Create an empty MultiUnion. Graphs can be added to the union using addGraph.
   */
  public DisjointMultiUnion() {
    super();
  }

  /**
   * Create a MultiUnion with the given graphs as components.
   *
   * @param graphs the component graphs of the union
   */
  public DisjointMultiUnion(Graph... graphs) {
    super(graphs);
  }

  /**
   * Create a MultiUnion with the given graphs as components.
   *
   * @param graphs the component graphs of the union
   */
  public DisjointMultiUnion(Iterator<Graph> graphs) {
    super(graphs);
  }

  /**
   * Answer an iterator over the triples in the union of the graphs in this composition.
   *
   * <p><b>Note</b>
   * that this implementation does not eliminate duplicates, so if the same triple appears in
   * multiple component graphs, it will appear multiple times in the result.
   * This is a deviation from the standard MultiUnion implementation, which eliminates duplicates.
   *
   * @param t The matcher to match against
   * @return An iterator of all triples matching t in the union of the graphs.
   */
  @Override
  public ExtendedIterator<Triple> graphBaseFind(
      Triple t) { // optimise the case where there's only one component graph.
    ExtendedIterator<Triple> iter = NullIterator.instance();
    for (var g : m_subGraphs) {
      iter = iter.andThen(g.find(t));
    }
    return iter;
  }

  /**
   * Answer an iterator over all the triples in the union of the graphs in this composition.
   * This implementation does not eliminate duplicates, so if the same triple appears in multiple
   * component graphs, it will appear multiple times in the result.
   * This is a deviation from the standard MultiUnion implementation, which eliminates duplicates.
   *
   * @return An iterator of all triples in the union of the graphs.
   */
  @Override
  public ExtendedIterator<Triple> find() {
    ExtendedIterator<Triple> iter = NullIterator.instance();
    for (var g : m_subGraphs) {
      iter = iter.andThen(g.find());
    }
    return iter;
  }

  /**
   * Answer a stream over the triples in the union of the graphs in this composition.
   * This implementation does not eliminate duplicates, so if the same triple appears in multiple
   * component graphs, it will appear multiple times in the result.
   * This is a deviation from the standard MultiUnion implementation, which eliminates duplicates.
   *
   * @param s the subject node to match, or Node.ANY for a wildcard
   * @param p the predicate node to match, or Node.ANY for a wildcard
   * @param o the object node to match, or Node.ANY for a wildcard
   * @return a stream of all triples matching the given pattern in the union of the graphs.
   */
  @Override
  public Stream<Triple> stream(Node s, Node p, Node o) {
    return m_subGraphs.stream()
        .flatMap(g -> g.stream(s, p, o));
  }

  /**
   * Answer a stream over all the triples in the union of the graphs in this composition.
   * This implementation does not eliminate duplicates, so if the same triple appears in multiple
   * component graphs, it will appear multiple times in the result.
   * This is a deviation from the standard MultiUnion implementation, which eliminates duplicates.
   *
   * @return a stream of all triples in the union of the graphs.
   */
  @Override
  public Stream<Triple> stream() {
    return m_subGraphs.stream()
        .flatMap(Graph::stream);
  }

  /**
   * Answer the number of triples in the union of the graphs in this composition.
   * This implementation does not eliminate duplicates, so if the same triple appears in multiple
   * component graphs, it will be counted multiple times in the result.
   * This is a deviation from the standard MultiUnion implementation, which eliminates duplicates.
   *
   * @return The number of triples in the union of the graphs.
   */
  @Override
  protected int graphBaseSize() {
    var size = 0;
    for (var g : m_subGraphs) {
      size += g.size();
    }
    return size;
  }
}
