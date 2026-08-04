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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.schema.SchemaIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jena.graph.Node;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps CIM IRI nodes to their declaration location in RDFS profile source files.
 *
 * <p>Built once at schema load time by scanning each profile file for IRI fragments. The result is
 * an immutable {@code Node → Location} map that powers both {@code textDocument/definition}
 * (go-to-definition) and {@code workspace/symbol} search.
 *
 * <h2>Scanning strategy</h2>
 *
 * <p>For <b>RDF/XML</b> profile files (ENTSO-E RDFS format), declaration lines carry {@code
 * rdf:about="…#LocalName"} or {@code rdf:about="#LocalName"}. These are matched first and take
 * priority over any subsequent reference to the same fragment.
 *
 * <p>For <b>Turtle</b> files, full-IRI subject triples carry {@code <…#LocalName>} as the first
 * token on the line, matched by the fragment fallback pattern.
 */
final class DefinitionIndex {

  private static final Logger LOG = LoggerFactory.getLogger(DefinitionIndex.class);

  static final int MAX_SYMBOLS = 100;

  /** Matches {@code rdf:about="…#LocalName"} — the declaration form in RDF/XML. */
  private static final Pattern ABOUT_PATTERN =
      Pattern.compile("rdf:about=\"[^\"]*#([A-Za-z][A-Za-z0-9._-]*)\"");

  /**
   * Matches any IRI fragment occurrence: {@code #LocalName} followed by a delimiter. Covers both
   * RDF/XML ({@code "}) and Turtle ({@code >}) contexts as a fallback.
   */
  private static final Pattern FRAGMENT_PATTERN =
      Pattern.compile("#([A-Za-z][A-Za-z0-9._-]*)(?:[\"'>\\s;,])");

  /**
   * Every declaration of a term, one per profile that declares it, in the schema's profile order.
   *
   * <p>A CIM term is routinely declared in several profiles — {@code cim:ACLineSegment} appears in
   * Equipment, and again wherever else it is used. All of them are kept so go-to-definition can
   * offer the choice instead of silently landing in whichever profile came first.
   */
  private final Map<Node, List<Location>> locations;

  private DefinitionIndex(Map<Node, List<Location>> locations) {
    this.locations = Collections.unmodifiableMap(locations);
  }

  /**
   * Returns the first declaration {@link Location} for {@code term}, or empty if not indexed. Used
   * where a single location is all that can be shown (workspace symbols).
   */
  Optional<Location> locationOf(Node term) {
    return locationsOf(term).stream().findFirst();
  }

  /**
   * Returns every declaration of {@code term} — one per profile declaring it — or an empty list if
   * it is not indexed. Editors show a chooser when there is more than one.
   */
  List<Location> locationsOf(Node term) {
    return locations.getOrDefault(term, List.of());
  }

  /**
   * Returns workspace symbols whose local name contains {@code query} (case-insensitive). Results
   * are capped at {@link #MAX_SYMBOLS} and sorted alphabetically by name. Symbols without a known
   * source location are omitted.
   */
  List<WorkspaceSymbol> findSymbols(String query, SchemaIndex index) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    var result = new ArrayList<WorkspaceSymbol>();

    for (Node cls : index.allClasses()) {
      addSymbol(cls, q, SymbolKind.Class, result);
    }
    for (Node prop : index.allProperties()) {
      addSymbol(prop, q, SymbolKind.Property, result);
    }
    for (Node member : index.allEnumMembers()) {
      addSymbol(member, q, SymbolKind.EnumMember, result);
    }

    result.sort(Comparator.comparing(WorkspaceSymbol::getName));
    if (result.size() > MAX_SYMBOLS) {
      return result.subList(0, MAX_SYMBOLS);
    }
    return result;
  }

  private void addSymbol(Node term, String query, SymbolKind kind, List<WorkspaceSymbol> out) {
    if (!term.isURI()) {
      return;
    }
    String local = localName(term.getURI());
    if (!local.toLowerCase(Locale.ROOT).contains(query)) {
      return;
    }
    Either<Location, WorkspaceSymbolLocation> locEither =
        locationOf(term)
            .<Either<Location, WorkspaceSymbolLocation>>map(Either::forLeft)
            .orElseGet(() -> Either.forRight(new WorkspaceSymbolLocation("")));
    out.add(new WorkspaceSymbol(local, kind, locEither));
  }

  // ---- Factory ---------------------------------------------------------------------------

  /**
   * Scans all source files referenced by {@code sourcePaths} and builds the declaration map. Each
   * file is read once; the mapping is built in a single pass.
   *
   * @param index the schema index providing all known classes and properties
   * @param sourcePaths map from profile version IRI to the file that declares it
   */
  static DefinitionIndex build(SchemaIndex index, Map<VersionIri, Path> sourcePaths) {
    if (sourcePaths.isEmpty()) {
      return new DefinitionIndex(new LinkedHashMap<>());
    }

    // Scan each unique source file once and record fragment → line number.
    var uniqueFiles = new LinkedHashSet<>(sourcePaths.values());
    var fileFragments = new LinkedHashMap<Path, Map<String, Integer>>();
    for (Path file : uniqueFiles) {
      try {
        fileFragments.put(file, scanFragments(file));
      } catch (IOException e) {
        LOG.warn("Cannot scan {}: {}", file, e.getMessage());
      }
    }

    var locations = new LinkedHashMap<Node, List<Location>>();

    for (Node cls : index.allClasses()) {
      if (!locations.containsKey(cls)) {
        findLocation(cls, index.findClass(cls), sourcePaths, fileFragments, locations);
      }
    }
    for (Node prop : index.allProperties()) {
      if (!locations.containsKey(prop)) {
        findLocation(prop, index.findProperty(prop), sourcePaths, fileFragments, locations);
      }
    }
    for (Node member : index.allEnumMembers()) {
      if (!locations.containsKey(member)) {
        findLocation(member, index.findEnumMember(member), sourcePaths, fileFragments, locations);
      }
    }

    LOG.debug(
        "DefinitionIndex: {} locations indexed from {} files",
        locations.size(),
        uniqueFiles.size());
    return new DefinitionIndex(locations);
  }

  /**
   * Records a location for every profile that declares {@code term} and whose file has the
   * fragment. Two profiles sharing one file (or declaring the term on the same line) contribute
   * once — a chooser offering the same place twice would be noise.
   */
  private static void findLocation(
      Node term,
      List<VersionIri> profiles,
      Map<VersionIri, Path> sourcePaths,
      Map<Path, Map<String, Integer>> fileFragments,
      Map<Node, List<Location>> out) {
    String local = localName(term.getURI());
    if (local.isEmpty()) {
      return;
    }
    var found = new ArrayList<Location>();
    var seen = new LinkedHashSet<String>();
    for (VersionIri v : orderedProfiles(profiles)) {
      Path file = sourcePaths.get(v);
      if (file == null) {
        continue;
      }
      Map<String, Integer> fragments = fileFragments.get(file);
      if (fragments == null) {
        continue;
      }
      Integer lineNo = fragments.get(local);
      if (lineNo != null && seen.add(file + ":" + lineNo)) {
        String fileUri = file.toUri().toString();
        found.add(
            new Location(fileUri, new Range(new Position(lineNo, 0), new Position(lineNo, 0))));
      }
    }
    if (!found.isEmpty()) {
      out.put(term, List.copyOf(found));
    }
  }

  /**
   * Scans {@code file} and returns a map of fragment identifier → 0-based line number.
   *
   * <p>Declaration lines (containing {@code rdf:about="…#fragment"}) take priority over any earlier
   * reference to the same fragment. This ensures go-to-definition lands on the {@code <rdfs:Class>}
   * or {@code <rdf:Property>} block, not on a {@code rdfs:domain} reference to the same IRI.
   */
  static Map<String, Integer> scanFragments(Path file) throws IOException {
    var references = new LinkedHashMap<String, Integer>();
    var declarations = new LinkedHashMap<String, Integer>();

    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);

      Matcher m1 = ABOUT_PATTERN.matcher(line);
      while (m1.find()) {
        declarations.putIfAbsent(m1.group(1), i);
      }

      Matcher m2 = FRAGMENT_PATTERN.matcher(line);
      while (m2.find()) {
        references.putIfAbsent(m2.group(1), i);
      }
    }

    // Declarations override first-occurrence references.
    var result = new LinkedHashMap<>(references);
    result.putAll(declarations);
    return result;
  }

  /**
   * The profiles of a term, in a stable order.
   *
   * <p>The schema index holds its profiles in an immutable map, whose iteration order is not the
   * order they were added in — so which profile "comes first" would otherwise vary between JVM
   * runs, and with it both the entry a chooser preselects and the single location reported to
   * callers that want one. Sorting by version IRI makes it the same every time.
   */
  static List<VersionIri> orderedProfiles(List<VersionIri> profiles) {
    return profiles.stream().sorted(Comparator.comparing(VersionIri::iri)).toList();
  }

  static String localName(String iri) {
    int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
    return last >= 0 ? iri.substring(last + 1) : iri;
  }
}
