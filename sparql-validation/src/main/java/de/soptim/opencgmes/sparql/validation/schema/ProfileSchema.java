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

import java.util.Objects;
import java.util.Set;

/**
 * Pre-computed class and property index for one profile, keyed by its {@link VersionIri}.
 *
 * @param versionIri  identity of the profile
 * @param classes     class URIs declared in the profile (rdfs:Class, owl:Class, domain/range subjects/objects)
 * @param properties  property URIs declared in the profile (rdf:Property, owl:*Property, properties with rdfs:domain/range)
 */
public record ProfileSchema(VersionIri versionIri, Set<Node> classes, Set<Node> properties) {

    public ProfileSchema {
        Objects.requireNonNull(versionIri, "versionIri");
        classes = Set.copyOf(classes);
        properties = Set.copyOf(properties);
    }
}
