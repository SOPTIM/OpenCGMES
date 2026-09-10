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

import java.util.Set;
import org.apache.jena.graph.Node;

/**
 * Everything a {@link CimProfile} says about itself, read in one pass.
 *
 * <p>Each accessor on {@link CimProfile} scans the graph on its own, so a caller that wants to
 * present a profile — a name, a version, a description — would walk it once per field.
 * {@link CimProfile#getMetadata()} collects them together instead.</p>
 *
 * <p>Which fields are populated depends on the CIM version the profile was written in. CGMES
 * 2.4.15 has no ontology object and therefore no version info; CGMES 3.0 carries all of them.
 * Every field except {@code versionIris} may be null.</p>
 *
 * @param cimNamespace  the URI bound to the {@code cim} prefix
 * @param headerProfile whether this describes a model or document header rather than a profile
 * @param keyword       the profile's abbreviation, e.g. "EQ"
 * @param label         the profile's human-readable name, e.g. "Core Equipment Vocabulary"
 * @param description   a prose description of the profile's purpose
 * @param versionIris   the version IRIs identifying this profile, possibly several, never null
 * @param versionInfo   the profile's version, e.g. "3.0.0"; absent in CGMES 2.4.15
 * @param issued        the date the profile was issued, in the lexical form it is written in
 */
public record CimProfileMetadata(
    String cimNamespace,
    boolean headerProfile,
    String keyword,
    String label,
    String description,
    Set<Node> versionIris,
    String versionInfo,
    String issued) {

  /** Defends the record against a null or mutable set of version IRIs. */
  public CimProfileMetadata {
    versionIris = versionIris == null ? Set.of() : Set.copyOf(versionIris);
  }
}
