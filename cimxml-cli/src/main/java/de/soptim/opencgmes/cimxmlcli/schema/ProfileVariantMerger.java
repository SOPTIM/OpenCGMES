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
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDFS;

/**
 * Merges variants of one RDFS profile, i.e. files with the same keyword and version IRIs like the
 * CGMES 2.4.15 EQ files Core, Core+Operation and Core+ShortCircuit, into a single profile. The
 * variants must not contradict each other: a class or property they share must have the same
 * domain, range, datatype, superclass and multiplicity in each of them.
 */
final class ProfileVariantMerger {

  /** Predicates that define a class or property; other triples like labels may differ. */
  private static final List<Node> DEFINING =
      List.of(
          RDFS.domain.asNode(),
          RDFS.range.asNode(),
          RDFS.subClassOf.asNode(),
          NodeFactory.createURI(CimProfile.NS_CIMS + "dataType"),
          NodeFactory.createURI(CimProfile.NS_CIMS + "multiplicity"));

  private ProfileVariantMerger() {}

  /**
   * Merges {@code variants}. Contradictions are added to {@code problems}; then the first variant
   * is returned unmerged.
   */
  static LoadedSchema merge(List<LoadedSchema> variants, List<Problem> problems) {
    final var contradictions = contradictions(variants);
    if (!contradictions.isEmpty()) {
      problems.addAll(contradictions);
      return variants.getFirst();
    }
    final var union = GraphFactory.createGraphMem();
    for (var variant : variants) {
      variant.profile().find().forEachRemaining(union::add);
      variant
          .profile()
          .getPrefixMapping()
          .getNsPrefixMap()
          .forEach(
              (prefix, uri) -> {
                // The first variant wins on prefix clashes
                if (union.getPrefixMapping().getNsPrefixURI(prefix) == null) {
                  union.getPrefixMapping().setNsPrefix(prefix, uri);
                }
              });
    }
    final var path =
        variants.stream().map(LoadedSchema::displayPath).collect(Collectors.joining(" + "));
    return new LoadedSchema(path, CimProfile.wrap(union));
  }

  private static List<Problem> contradictions(List<LoadedSchema> variants) {
    // Per defining predicate and term: the values and where they were first seen
    final var seen = new HashMap<Node, Map<Node, Definition>>();
    final var problems = new ArrayList<Problem>();
    for (var variant : variants) {
      for (var predicate : DEFINING) {
        final var values = new HashMap<Node, Set<Node>>();
        variant
            .profile()
            .find(Node.ANY, predicate, Node.ANY)
            .forEachRemaining(
                t ->
                    values
                        .computeIfAbsent(t.getSubject(), k -> new HashSet<>())
                        .add(t.getObject()));
        final var byTerm = seen.computeIfAbsent(predicate, k -> new HashMap<>());
        values.forEach(
            (term, objects) -> {
              final var first =
                  byTerm.putIfAbsent(term, new Definition(variant.displayPath(), objects));
              if (first != null && !first.objects().equals(objects)) {
                problems.add(
                    new Problem(
                        variant.displayPath(),
                        localName(term)
                            + " has "
                            + localName(predicate)
                            + " "
                            + names(objects)
                            + ", but "
                            + names(first.objects())
                            + " in "
                            + first.path(),
                        "Variants of one profile must not contradict each other; remove the"
                            + " faulty file."));
              }
            });
      }
    }
    return problems;
  }

  private static String names(Set<Node> nodes) {
    return nodes.stream().map(ProfileVariantMerger::localName).sorted().toList().toString();
  }

  private static String localName(Node node) {
    if (!node.isURI()) {
      return node.toString();
    }
    final var uri = node.getURI();
    final var hash = uri.lastIndexOf('#');
    return hash >= 0 ? uri.substring(hash + 1) : uri;
  }

  /** The values of a defining predicate for one term, and the file they were first seen in. */
  private record Definition(String path, Set<Node> objects) {}
}
