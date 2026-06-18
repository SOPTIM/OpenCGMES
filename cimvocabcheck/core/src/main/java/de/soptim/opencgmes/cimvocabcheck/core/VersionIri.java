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

package de.soptim.opencgmes.cimvocabcheck.core;

import java.util.Objects;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * Identifier of an RDFS/CIM profile by its {@code owl:versionIRI}.
 *
 * <p>{@link de.soptim.opencgmes.cimxml.graph.CimProfile} natively exposes version IRIs as Jena
 * {@link Node} sets. This record wraps a single URI {@link Node} so the validation API can publish
 * profile identifiers without leaking the {@code Set<Node>} representation used by the existing CIM
 * profile registry.
 *
 * @param node the URI node for the profile, never {@code null}
 */
public record VersionIri(Node node) {

  /** Canonical constructor; requires a non-null URI node. */
  public VersionIri {
    Objects.requireNonNull(node, "node");
    if (!node.isURI()) {
      throw new IllegalArgumentException("VersionIri must be a URI node, got: " + node);
    }
  }

  /** Convenience constructor from a raw IRI string. */
  public static VersionIri of(String iri) {
    Objects.requireNonNull(iri, "iri");
    return new VersionIri(NodeFactory.createURI(iri));
  }

  /** Returns the IRI string of the wrapped node. */
  public String iri() {
    return node.getURI();
  }

  @Override
  public String toString() {
    return iri();
  }
}
