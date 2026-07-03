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
import org.apache.jena.graph.compose.Delta;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 * Faster alternative to {@link Delta}.
 */
public class FastDeltaGraph extends GraphBase {

  private final Graph base;
  private final Graph additions;
  private final Graph deletions;

  /**
   * Creates a new {@link FastDeltaGraph} that is based on the given {@code base} graph. The delta
   * graph will initially be empty, i.e. it will not contain any additions or deletions.
   *
   * @param base the base graph on which this delta graph is based
   */
  public FastDeltaGraph(Graph base) {
    super();
    if (base == null) {
      throw new IllegalArgumentException("base graph must not be null");
    }
    this.base = base;
    this.additions = GraphFactory.createGraphMem();
    this.deletions = GraphFactory.createGraphMem();
  }

  /**
   * Creates a new {@link FastDeltaGraph} that is based on the given {@code newBase} graph. This is
   * used to rebase a {@link FastDeltaGraph} on a new base graph. There are no checks performed to
   * ensure that the new base graph is compatible with the previous base graph.
   *
   * @param newBase            the new base graph
   * @param deltaGraphToRebase the delta graph to rebase
   */
  public FastDeltaGraph(Graph newBase, FastDeltaGraph deltaGraphToRebase) {
    super();
    if (newBase == null) {
      throw new IllegalArgumentException("base graph must not be null");
    }
    this.base = newBase;
    this.additions = deltaGraphToRebase.additions;
    this.deletions = deltaGraphToRebase.deletions;
  }

  /**
   * Creates a new {@link FastDeltaGraph} that is based on the given {@code base} graph and contains
   * the given additions and deletions. This constructor is used to create a delta graph with
   * predefined additions and deletions, for example when rebasing a delta graph on a new base
   * graph.
   *
   * @param base      the base graph on which this delta graph is based
   * @param additions the graph containing the triples that are added in this delta graph
   * @param deletions the graph containing the triples that are deleted in this delta graph
   */
  public FastDeltaGraph(Graph base, Graph additions, Graph deletions) {
    super();
    if (base == null) {
      throw new IllegalArgumentException("base graph must not be null");
    }
    this.base = base;
    this.additions = additions;
    this.deletions = deletions;
  }

  /**
   * Returns an iterator over the triples that are added in this delta graph.
   * These are the triples that are present in the delta graph but not in the base graph.
   *
   * @return an iterator over the added triples in this delta graph
   */
  public Iterator<Triple> getAdditions() {
    return additions.find();
  }

  /**
   * Returns an iterator over the triples that are deleted in this delta graph.
   * These are the triples that are present in the base graph but not in the delta graph.
   *
   * @return an iterator over the deleted triples in this delta graph
   */
  public Iterator<Triple> getDeletions() {
    return deletions.find();
  }

  /**
   * Checks if this delta graph has any changes compared to the base graph. A delta graph is
   * considered to have changes if it contains any additions or deletions.
   *
   * @return true if this delta graph has any changes, false otherwise
   */
  public boolean hasChanges() {
    return !additions.isEmpty() || !deletions.isEmpty();
  }

  /**
   * Returns the base graph on which this delta graph is based. The base graph represents the state
   * of the graph before any additions or deletions are applied.
   *
   * @return the base graph of this delta graph
   */
  public Graph getBase() {
    return base;
  }

  @Override
  public void performAdd(Triple t) {
    if (!base.contains(t)) {
      additions.add(t);
    }
    deletions.delete(t);
  }

  @Override
  public void performDelete(Triple t) {
    additions.delete(t);
    if (base.contains(t)) {
      deletions.add(t);
    }
  }

  @Override
  protected boolean graphBaseContains(Triple t) {
    if (t.isConcrete()) {
      if (base.contains(t)) {
        return !deletions.contains(t);
      }
      return additions.contains(t);
    } else {
      return graphBaseFind(t).hasNext();
    }
  }

  @Override
  protected ExtendedIterator<Triple> graphBaseFind(Triple triplePattern) {
    return base.find(triplePattern)
        .filterDrop(deletions::contains)
        .andThen(additions.find(triplePattern));
  }

  @Override
  public ExtendedIterator<Triple> find() {
    return base.find()
        .filterDrop(deletions::contains)
        .andThen(additions.find());
  }

  @Override
  public Stream<Triple> stream() {
    return Stream.concat(
        base.stream().filter(t -> !deletions.contains(t)),
        additions.stream());
  }

  @Override
  public Stream<Triple> stream(Node s, Node p, Node o) {
    return Stream.concat(
        base.stream(s, p, o).filter(t -> !deletions.contains(t)),
        additions.stream(s, p, o));
  }

  @Override
  public void close() {
    super.close();
    base.close();
    additions.close();
    deletions.close();
  }

  @Override
  public int graphBaseSize() {
    return base.size() + additions.size() - deletions.size();
  }
}
