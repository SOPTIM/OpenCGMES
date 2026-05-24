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

package de.soptim.opencgmes.cimcheck.core.analysis;

import org.apache.jena.update.UpdateRequest;

import java.util.List;
import java.util.Objects;

/**
 * Result of a static {@link SparqlQueryAnalyzer} pass over a SPARQL Update request.
 *
 * <p>A single {@code UpdateRequest} may contain multiple update operations separated by {@code ;}.
 * All references (classes, properties, graphs) from every operation are aggregated into the
 * collections of this record.</p>
 *
 * <p>The collections are de-duplicated, in source order. A {@code dynamicPredicate} flag of
 * {@code true} means at least one template or WHERE-clause triple used a variable predicate.</p>
 */
public record SparqlUpdateAnalysis(
        UpdateRequest updateRequest,
        List<TriplePatternReference> triples,
        List<ClassReference> classes,
        List<PropertyReference> properties,
        List<GraphReference> graphs,
        List<PathChainReference> pathChains,
        boolean dynamicPredicate,
        boolean dynamicClass
) {

    public SparqlUpdateAnalysis {
        Objects.requireNonNull(updateRequest, "updateRequest");
        triples    = List.copyOf(triples);
        classes    = List.copyOf(classes);
        properties = List.copyOf(properties);
        graphs     = List.copyOf(graphs);
        pathChains = pathChains == null ? List.of() : List.copyOf(pathChains);
    }
}
