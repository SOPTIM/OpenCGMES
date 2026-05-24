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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Look-up structure used by the validator to decide whether a class or property IRI exists in
 * a given subset of profiles, and to compute "exists elsewhere" hints.
 *
 * <p>Semantic lookups ({@link #domainsOf}, {@link #rangesOf}, {@link #superClassesOf},
 * {@link #isSubClassOf}) have default implementations that return empty results, so
 * callers that only need existence checks keep working without any extra wiring.</p>
 *
 * <p>Implementations are expected to be immutable and safe for concurrent reads.</p>
 */
public interface SchemaIndex {

    /** @return {@code true} iff {@code classUri} is declared in at least one of {@code profiles}. */
    boolean classExists(Node classUri, Collection<VersionIri> profiles);

    /** @return {@code true} iff {@code propertyUri} is declared in at least one of {@code profiles}. */
    boolean propertyExists(Node propertyUri, Collection<VersionIri> profiles);

    /** @return all known profiles declaring {@code classUri}, possibly empty. */
    List<VersionIri> findClass(Node classUri);

    /** @return all known profiles declaring {@code propertyUri}, possibly empty. */
    List<VersionIri> findProperty(Node propertyUri);

    /** @return every profile registered in this index. */
    List<VersionIri> getAllProfiles();

    // ---- Semantic lookups ------------------------------------------------------------------

    /**
     * Union of {@code rdfs:domain} classes declared for {@code propertyUri} across the scope.
     * Empty result means "no domain known" — which the validator interprets as <em>permissive</em>
     * (the property is allowed on any class).
     */
    default Set<Node> domainsOf(Node propertyUri, Collection<VersionIri> profiles) {
        return Set.of();
    }

    /**
     * Union of {@code rdfs:range} class/datatype URIs declared for {@code propertyUri} across
     * the scope. Empty result means "no range known".
     */
    default Set<Node> rangesOf(Node propertyUri, Collection<VersionIri> profiles) {
        return Set.of();
    }

    /**
     * Transitive {@code rdfs:subClassOf} closure of {@code classUri} within the scope, inclusive
     * of {@code classUri} itself. Cycles are handled with a visited set.
     */
    default Set<Node> superClassesOf(Node classUri, Collection<VersionIri> profiles) {
        return Set.of(classUri);
    }

    /** @return {@code true} iff {@code sub} ⊑ {@code sup} in the {@code rdfs:subClassOf} closure. */
    default boolean isSubClassOf(Node sub, Node sup, Collection<VersionIri> profiles) {
        if (sub == null || sup == null) return false;
        if (sub.equals(sup)) return true;
        return superClassesOf(sub, profiles).contains(sup);
    }

    // ---- Documentation lookups (populated from rdfs:label / rdfs:comment) -----------------

    /**
     * Returns the first {@code rdfs:label} found for {@code term} across the given scope,
     * or empty if none is recorded.
     */
    default Optional<String> labelOf(Node term, Collection<VersionIri> scope) {
        return Optional.empty();
    }

    /**
     * Returns the first {@code rdfs:comment} found for {@code term} across the given scope,
     * or empty if none is recorded.
     */
    default Optional<String> commentOf(Node term, Collection<VersionIri> scope) {
        return Optional.empty();
    }

    // ---- Enumeration (used for completion) -------------------------------------------------

    /** All class nodes registered across every profile in this index. */
    default Set<Node> allClasses() {
        return Set.of();
    }

    /** All property nodes registered across every profile in this index. */
    default Set<Node> allProperties() {
        return Set.of();
    }
}
