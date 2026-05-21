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
import java.util.List;

/**
 * Look-up structure used by the validator to decide whether a class or property IRI exists in
 * a given subset of profiles, and to compute "exists elsewhere" hints.
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
}
