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

import java.util.Objects;
import org.apache.jena.graph.Graph;

/**
 * A specialization of {@link Graph} that provides methods to determine the 'cim' namespace of the
 * graph based on its namespace prefixes.
 */
public interface CimGraph extends Graph {

  /**
   * Get the CIM namespace URI associated with the 'cim' prefix in the given graph's prefix
   * mapping.
   *
   * @param graph the graph from which to retrieve the CIM namespace
   * @return the CIM namespace URI or null if the 'cim' prefix is not defined in the graph's prefix
   *     mapping
   * @throws NullPointerException if the graph is null
   */
  static String getCimNs(Graph graph) {
    Objects.requireNonNull(graph, "graph");
    return graph.getPrefixMapping().getNsPrefixURI("cim");
  }

  /**
   * Get the CIM namespace URI associated with the 'cim' prefix in the graph's prefix mapping.
   *
   * @return the CIM namespace URI or null if the 'cim' prefix is not defined in the graph's prefix
   *     mapping
   */
  default String getCimNamespace() {
    return getCimNs(this);
  }
}
