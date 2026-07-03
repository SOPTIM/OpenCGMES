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

package de.soptim.opencgmes.cimxml.sparql.core;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapStd;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphCollection;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Transactional;
import org.apache.jena.sparql.core.TransactionalLock;

/**
 * A {@link DatasetGraph} that holds a set of named graphs and an optional default graph.
 *
 * <p>This implementation provides "best effort" transactions; it only provides MRPlusSW locking. So
 * all graphs that are transactional must support MRPlusSW locking.
 *
 * <p>Only the transactional graphs are included in the transaction.
 */
public class LinkedCimDatasetGraph extends DatasetGraphCollection implements CimDatasetGraph {

  protected final PrefixMap prefixes = new PrefixMapStd();
  private final ConcurrentMap<Node, Graph> graphs = new ConcurrentHashMap<>();
  private final ConcurrentMap<Node, Transactional> transactionalGraphs = new ConcurrentHashMap<>();
  private final TransactionalLock txn = TransactionalLock.createMRPlusSW();

  /**
   * Creates a new empty LinkedCimDatasetGraph with no default graph and no named graphs.
   */
  public LinkedCimDatasetGraph() {
    super();
  }

  /**
   * Creates a new LinkedCimDatasetGraph with the given default graph and no named graphs.
   * The defaultgraph is associated with the default graph name (Quad.defaultGraphIRI).
   *
   * @param defaultGraph the default graph to be used in this dataset
   */
  public LinkedCimDatasetGraph(Graph defaultGraph) {
    this();
    addGraph(graphs, transactionalGraphs, Quad.defaultGraphIRI, defaultGraph);
  }

  public Collection<Graph> getGraphs() {
    return graphs.values();
  }

  @Override
  public Iterator<Node> listGraphNodes() {
    return graphs.keySet().iterator();
  }

  @Override
  public PrefixMap prefixes() {
    return prefixes;
  }

  @Override
  public boolean supportsTransactions() {
    return true;
  }

  @Override
  public Graph getDefaultGraph() {
    return graphs.getOrDefault(Quad.defaultGraphIRI, Graph.emptyGraph);
  }

  @Override
  public Graph getGraph(Node graphNode) {
    return graphs.get(graphNode);
  }

  private static void addGraph(Map<Node, Graph> namedGraphs,
      Map<Node, Transactional> namedTransactionals,
      Node graphName, Graph graph) {
    namedGraphs.put(graphName, graph);
    if (graph instanceof Transactional transactional) {
      namedTransactionals.put(graphName, transactional);
    }
  }

  @Override
  public void addGraph(Node graphName, Graph graph) {
    addGraph(graphs, transactionalGraphs, graphName, graph);
  }

  @Override
  public void removeGraph(Node graphName) {
    graphs.remove(graphName);
    transactionalGraphs.remove(graphName);
  }

  @Override
  public void begin(TxnType type) {
    txn.begin(type);
    var openedTransactions = new ArrayList<Transactional>();
    try {
      for (Transactional transactional : transactionalGraphs.values()) {
        transactional.begin(type);
        openedTransactions.add(transactional);
      }
    } catch (Exception e) {
      txn.abort();
      for (Transactional transactional : openedTransactions) {
        transactional.abort();
      }
      throw e;
    }
  }

  @Override
  public boolean promote(Promote mode) {
    return false;
  }

  @Override
  public void commit() {
    txn.commit();
    var failedCommits = new ArrayList<Exception>();
    for (Transactional transactional : transactionalGraphs.values()) {
      try {
        transactional.commit();
      } catch (Exception e) {
        failedCommits.add(e);
      }
    }
    if (!failedCommits.isEmpty()) {
      // Exception with message "Failed to commit transactions on x graphs"
      throw new LinkedDatasetTransactionException(
          "Failed to commit transactions on " + failedCommits.size() + " graphs", failedCommits);
    }
  }

  @Override
  public void abort() {
    txn.abort();
    var failedAborts = new ArrayList<Exception>();
    for (Transactional transactional : transactionalGraphs.values()) {
      try {
        transactional.abort();
      } catch (Exception e) {
        failedAborts.add(e);
      }
    }
    if (!failedAborts.isEmpty()) {
      // Exception with message "Failed to abort transactions on x graphs"
      throw new LinkedDatasetTransactionException(
          "Failed to abort transactions on " + failedAborts.size() + " graphs", failedAborts);
    }
  }

  @Override
  public void end() {
    txn.end();
    var failedEnds = new ArrayList<Exception>();
    for (Transactional transactional : transactionalGraphs.values()) {
      try {
        transactional.end();
      } catch (Exception e) {
        failedEnds.add(e);
      }
    }
    if (!failedEnds.isEmpty()) {
      // Exception with message "Failed to end transaction on x graphs"
      throw new LinkedDatasetTransactionException(
          "Failed to end transaction on " + failedEnds.size() + " graphs", failedEnds);
    }
  }

  @Override
  public ReadWrite transactionMode() {
    return txn.transactionMode();
  }

  @Override
  public TxnType transactionType() {
    return txn.transactionType();
  }

  @Override
  public boolean isInTransaction() {
    return txn.isInTransaction();
  }

  /**
   * Exception thrown when one or more transactional graphs fail to commit, abort,
   * or end a transaction.
   * This exception contains a list of the individual exceptions that occurred
   * during the transaction operation.
   */
  public static class LinkedDatasetTransactionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ArrayList<Exception> exceptions;

    /**
     * Creates a new LinkedDatasetTransactionException with the given message
     * and collection of exceptions.
     *
     * @param message the detail message for this exception
     * @param exceptions the list of exceptions that occurred during the transaction operation
     */
    public LinkedDatasetTransactionException(String message, ArrayList<Exception> exceptions) {
      super(message);
      this.exceptions = exceptions;
    }

    /**
     * Returns the list of exceptions that occurred during the transaction operation.
     *
     * @return the list of exceptions
     */
    public List<Exception> getExceptions() {
      return exceptions;
    }
  }
}
