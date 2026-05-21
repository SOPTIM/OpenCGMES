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

package de.soptim.opencgmes.sparql.validation.schema;

import de.soptim.opencgmes.sparql.validation.VersionIri;
import org.apache.jena.graph.Node;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schema-resolution policy for a single validation call.
 *
 * <p>The three subtypes mirror the three {@code validateSparql(...)} overloads. They are
 * exposed so the analyzer/validator implementation, the dependency methods and unit tests can
 * share a single shape; callers normally don't construct them directly.</p>
 */
public sealed interface ValidationScope
        permits ValidationScope.AllProfilesScope,
                ValidationScope.ProfileListScope,
                ValidationScope.NamedGraphProfileScope {

    /** Validate against every profile known to the {@link SchemaIndex}. */
    record AllProfilesScope() implements ValidationScope {}

    /** Validate against an explicit list of profiles; FROM/FROM NAMED in the query is ignored. */
    record ProfileListScope(Collection<VersionIri> profiles) implements ValidationScope {
        public ProfileListScope {
            Objects.requireNonNull(profiles, "profiles");
            profiles = List.copyOf(profiles);
        }
    }

    /**
     * Validate terms inside {@code GRAPH <g> { ... }} blocks against the profiles mapped to
     * {@code <g>}. Terms outside named-graph blocks are validated against the union of all
     * profiles in the map. Graphs referenced by the query but absent from the map produce a
     * {@code GRAPH_NOT_CONFIGURED} warning.
     */
    record NamedGraphProfileScope(Map<Node, Collection<VersionIri>> namedGraphsToProfiles)
            implements ValidationScope {

        public NamedGraphProfileScope {
            Objects.requireNonNull(namedGraphsToProfiles, "namedGraphsToProfiles");
            // Defensive copy with deep-immutable values.
            var copy = new LinkedHashMap<Node, Collection<VersionIri>>();
            for (var e : namedGraphsToProfiles.entrySet()) {
                copy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            namedGraphsToProfiles = Map.copyOf(copy);
        }
    }
}
