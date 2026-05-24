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

package de.soptim.opencgmes.sparql.validation.analysis;

import org.apache.jena.graph.Node;

/**
 * Reference to a named graph used by the query.
 *
 * @param graph   the graph URI node
 * @param source  where in the query the graph was found
 */
public record GraphReference(Node graph, Source source) {

    public enum Source {
        /** Explicit {@code GRAPH <g> { ... }} block. */
        GRAPH_BLOCK,
        /** {@code FROM NAMED <g>} clause. */
        FROM_NAMED,
        /** {@code FROM <g>} clause (default-graph composition). */
        FROM,
        /** Named graph appearing in an INSERT or DELETE quad template. */
        UPDATE_TEMPLATE,
        /** {@code WITH <g>} clause in an INSERT/DELETE update operation. */
        UPDATE_WITH,
        /** Graph named in a {@code CREATE}, {@code DROP}, or {@code CLEAR} operation. */
        UPDATE_MANAGEMENT
    }
}
