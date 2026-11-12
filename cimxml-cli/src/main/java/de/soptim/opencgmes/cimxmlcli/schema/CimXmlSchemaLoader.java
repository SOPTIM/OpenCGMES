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

package de.soptim.opencgmes.cimxmlcli.schema;

import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import de.soptim.opencgmes.cimxmlcli.source.CimSource;
import de.soptim.opencgmes.cimxmlcli.source.SourceException;
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.atlas.RuntimeIOException;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.RiotException;

/**
 * Loads RDFS profiles with the cimxml {@link RdfXmlParser} and registers them in a {@link
 * CimProfileRegistry}.
 *
 * <p>The registry accepts only one header profile per CIM namespace and only one profile per set of
 * version IRIs. Duplicates are handled before registering:
 *
 * <ul>
 *   <li>Profiles with the same version IRIs <em>and</em> the same keyword are variants of one
 *       profile; CGMES 2.4.15 ships four EQ variants (Core, Core+Operation, ...) that all declare
 *       the same IRIs. They are merged into one profile with a warning, unless they contradict each
 *       other (see {@link ProfileVariantMerger}).
 *   <li>Profiles with the same version IRIs but different keywords, and a second header profile for
 *       the same CIM namespace, are reported as problems naming both files.
 * </ul>
 */
public final class CimXmlSchemaLoader {

  private final RdfXmlParser parser;
  private final CimProfileRegistry registry;

  /** Creates a loader that registers the profiles in {@code registry}. */
  public CimXmlSchemaLoader(RdfXmlParser parser, CimProfileRegistry registry) {
    this.parser = parser;
    this.registry = registry;
  }

  /** Parses the RDFS profile in {@code source}, without registering it. */
  public LoadedSchema load(CimSource source) throws SourceException {
    final var path = source.displayPath();
    try (var in = source.openStream()) {
      return new LoadedSchema(path, parser.parseCimProfile(in));
    } catch (IllegalArgumentException e) {
      throw new SourceException(
          new Problem(
              path,
              "not a CGMES RDFS profile: " + e.getMessage(),
              "Only put CGMES RDFS profile files into the schema folder."),
          e);
    } catch (IOException | RiotException | RuntimeIOException e) {
      throw new SourceException(Problem.of(path, "cannot parse RDFS: " + e.getMessage()), e);
    }
  }

  /**
   * Registers the given profiles, resolving or reporting duplicates. All profiles are parsed first
   * and registered together, so that duplicates can be resolved with all candidates known.
   */
  public SchemaRegistration registerAll(List<LoadedSchema> schemas) {
    final var problems = new ArrayList<Problem>();
    final var warnings = new ArrayList<Problem>();
    // Candidates per registry slot, in the order they were found
    final var groups = new LinkedHashMap<Object, List<LoadedSchema>>();
    for (var schema : schemas) {
      groups.computeIfAbsent(groupKey(schema.profile()), k -> new ArrayList<>()).add(schema);
    }
    final var registered = new ArrayList<LoadedSchema>();
    for (var group : groups.values()) {
      final var schema = select(group, problems, warnings);
      try {
        registry.register(schema.profile());
        registered.add(schema);
      } catch (IllegalArgumentException e) {
        problems.add(
            Problem.of(schema.displayPath(), "cannot register profile: " + e.getMessage()));
      }
    }
    return new SchemaRegistration(registered, problems, warnings);
  }

  /** Header profiles are unique per CIM namespace, all others per set of version IRIs. */
  private static Object groupKey(CimProfile profile) {
    return profile.isHeaderProfile()
        ? "FH " + profile.getCimNamespace()
        : Set.copyOf(profile.getOwlVersionIris());
  }

  /** Returns the schema to register for {@code group} and reports duplicates. */
  private static LoadedSchema select(
      List<LoadedSchema> group, List<Problem> problems, List<Problem> warnings) {
    final var first = group.getFirst();
    if (group.size() == 1) {
      return first;
    }
    final var profile = first.profile();
    final var sameKeyword =
        group.stream().map(s -> s.profile().getDcatKeyword()).distinct().count() == 1;
    // Header or different keywords: no safe choice, the user decides
    if (profile.isHeaderProfile() || !sameKeyword) {
      final var what =
          profile.isHeaderProfile()
              ? "second file header (FH) profile for CIM namespace " + profile.getCimNamespace()
              : "profile with version IRI(s) " + versionIris(profile) + " is defined twice";
      for (var duplicate : group.subList(1, group.size())) {
        problems.add(
            new Problem(
                duplicate.displayPath(),
                what + " (already loaded from " + first.displayPath() + ")",
                "Remove one of: " + first.displayPath() + ", " + duplicate.displayPath()));
      }
      return first;
    }
    // Variants of one profile: merge them, unless they contradict each other
    final var problemsBefore = problems.size();
    final var merged = ProfileVariantMerger.merge(group, problems);
    if (problems.size() == problemsBefore) {
      warnings.add(
          new Problem(
              merged.displayPath(),
              group.size()
                  + " RDFS files define profile "
                  + profile.getDcatKeyword()
                  + " with the same version IRIs; they are merged into one profile",
              "Keep only one variant of the profile in the schema folder to avoid this warning."));
    }
    return merged;
  }

  private static String versionIris(CimProfile profile) {
    return profile.getOwlVersionIris().stream()
        .map(Node::getURI)
        .sorted()
        .collect(Collectors.joining(", ", "[", "]"));
  }
}
