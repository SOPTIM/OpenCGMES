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

package de.soptim.opencgmes.cimvocabcheck.core.analysis;

import org.apache.jena.graph.Node;

/**
 * Reference to a constant IRI used in an expression position — inside a {@code FILTER}, a {@code
 * VALUES} table, or a {@code BIND} assignment — rather than in a triple pattern.
 *
 * <p>These are the dominant way queries compare against enumeration values (e.g. {@code
 * FILTER(?kind = cim:WindGenUnitKind.offshore)} or {@code VALUES ?kind { cim:… }}) yet are not
 * reachable from the triple/class/property collections.
 *
 * @param constant the URI node used as a constant
 * @param graph enclosing {@code GRAPH <g>} node, or {@code null} for default-graph context
 * @param comparedVariable when the constant is compared against (or bound to) a single query
 *     variable — via {@code =}, {@code IN}, or a {@code VALUES} column — that variable; otherwise
 *     {@code null}. It gives the validator a handle for inferring the constant's expected type.
 */
public record ConstantReference(Node constant, Node graph, Node comparedVariable) {}
