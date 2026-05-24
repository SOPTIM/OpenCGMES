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

package de.soptim.opencgmes.cimcheck.core.schema;

import de.soptim.opencgmes.cimcheck.core.VersionIri;
import org.apache.jena.graph.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pre-computed index for one profile, keyed by its {@link VersionIri}.
 *
 * <p>The {@link #classes()} and {@link #properties()} sets are always indexed. The three
 * semantic maps ({@link #propertyDomain()}, {@link #propertyRange()}, {@link #subClassOf()}) are optional
 * — callers that only need existence checks can use
 * {@link #minimal(VersionIri, Set, Set)} to skip them.</p>
 *
 * @param versionIri       identity of the profile
 * @param classes          class URIs declared in the profile
 * @param properties       property URIs declared in the profile
 * @param propertyDomain   property URI → set of {@code rdfs:domain} class URIs (possibly empty)
 * @param propertyRange    property URI → set of {@code rdfs:range} class or datatype URIs
 * @param subClassOf       class URI → set of <em>direct</em> {@code rdfs:subClassOf} super-classes;
 *                         transitive closure is computed on demand by {@link SchemaIndex}
 * @param termLabels       term URI → {@code rdfs:label} literal (first found wins, may be empty)
 * @param termComments     term URI → {@code rdfs:comment} literal (first found wins, may be empty)
 */
public record ProfileSchema(
        VersionIri versionIri,
        Set<Node> classes,
        Set<Node> properties,
        Map<Node, Set<Node>> propertyDomain,
        Map<Node, Set<Node>> propertyRange,
        Map<Node, Set<Node>> subClassOf,
        Map<Node, String> termLabels,
        Map<Node, String> termComments
) {

    public ProfileSchema {
        Objects.requireNonNull(versionIri, "versionIri");
        classes = Set.copyOf(classes);
        properties = Set.copyOf(properties);
        propertyDomain = deepCopy(propertyDomain);
        propertyRange = deepCopy(propertyRange);
        subClassOf = deepCopy(subClassOf);
        termLabels = termLabels == null ? Map.of() : Map.copyOf(termLabels);
        termComments = termComments == null ? Map.of() : Map.copyOf(termComments);
    }

    /** Convenience for callers that only know class+property sets and no relations. */
    public static ProfileSchema minimal(VersionIri versionIri, Set<Node> classes, Set<Node> properties) {
        return new ProfileSchema(versionIri, classes, properties,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static Map<Node, Set<Node>> deepCopy(Map<Node, Set<Node>> in) {
        if (in == null || in.isEmpty()) return Map.of();
        var out = new LinkedHashMap<Node, Set<Node>>(in.size());
        for (var e : in.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            out.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        return Map.copyOf(out);
    }
}
