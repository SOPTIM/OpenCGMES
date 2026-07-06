/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimvocabcheck.core.config;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.graph.CimProfile16;
import de.soptim.opencgmes.cimxml.graph.CimProfile17;
import de.soptim.opencgmes.cimxml.graph.CimProfile18;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.apache.jena.graph.Graph;

/**
 * Maps the {@code cimNamespaces} config shape names to the built-in {@link CimProfile}
 * implementations that parse them.
 *
 * <p>{@code CimProfile16}/{@code CimProfile17}/{@code CimProfile18} are parsing strategies for an
 * ontology's conventions, not namespace-specific code — {@link CimProfile#getCimNamespace()} simply
 * reads back whatever {@code cim} namespace prefix the wrapped graph declares. A vendor's custom
 * {@code cim} namespace can therefore reuse one of these built-in shapes as long as its ontology
 * follows the same convention:
 *
 * <ul>
 *   <li>{@code cim16} — legacy CGMES 2.4.15 style: {@code cims:isFixed} version IRIs, {@code
 *       {Profile}Version.shortName} keywords.
 *   <li>{@code cim17} — CGMES 3.0+ style: {@code owl:versionIRI}, {@code dcat:keyword}.
 *   <li>{@code cim18} — like {@code cim17}, plus CIM18's {@code DocumentHeader}-based
 *       header-profile detection.
 * </ul>
 */
final class CimProfileShapes {

  private static final Map<String, Function<Graph, CimProfile>> SHAPES =
      Map.of(
          "cim16", CimProfile16::new,
          "cim17", CimProfile17::new,
          "cim18", CimProfile18::new);

  private CimProfileShapes() {}

  /**
   * Resolves a shape name (case-insensitive) to its {@link CimProfile} factory.
   *
   * @throws IllegalArgumentException if {@code shape} is not one of {@code cim16}/{@code
   *     cim17}/{@code cim18}
   */
  static Function<Graph, CimProfile> resolve(String shape) {
    Function<Graph, CimProfile> factory = SHAPES.get(shape.toLowerCase(Locale.ROOT));
    if (factory == null) {
      throw new IllegalArgumentException(
          "Unknown CIM profile shape '" + shape + "' — expected one of: " + SHAPES.keySet());
    }
    return factory;
  }
}
