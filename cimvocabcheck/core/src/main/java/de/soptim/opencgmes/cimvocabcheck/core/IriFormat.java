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

import java.util.Collection;

/**
 * Small helpers for rendering IRIs and profile lists in human-readable diagnostic messages.
 *
 * <p>Shared by {@link SparqlQueryValidator} and {@link
 * de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclShapeAnalyzer} so the two validators format
 * profile/term references identically.
 */
public final class IriFormat {

  private IriFormat() {}

  /**
   * Shortens an IRI to its last two path/fragment segments — e.g. {@code
   * http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0} becomes {@code CoreEquipment-EU/3.0}. Returns
   * the IRI unchanged when it has no separator.
   */
  public static String shortIri(String iri) {
    int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
    if (last < 0) {
      return iri;
    }
    int prev = Math.max(iri.lastIndexOf('/', last - 1), iri.lastIndexOf('#', last - 1));
    return prev >= 0 ? iri.substring(prev + 1) : iri.substring(last + 1);
  }

  /** Appends {@code [profile1, profile2, …]} (each {@link #shortIri shortened}) to {@code msg}. */
  public static void appendIris(StringBuilder msg, Collection<VersionIri> profiles) {
    msg.append('[');
    boolean first = true;
    for (VersionIri v : profiles) {
      if (!first) {
        msg.append(", ");
      }
      msg.append(shortIri(v.iri()));
      first = false;
    }
    msg.append(']');
  }
}
