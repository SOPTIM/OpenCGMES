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

package de.soptim.opencgmes.cimcheck.lsp;

import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.core.schema.SchemaIndex;
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

/**
 * Maps CIM IRI nodes to their declaration location in RDFS profile source files.
 *
 * <p>Built once at schema load time by scanning each profile file for IRI fragments.
 * The result is an immutable {@code Node → Location} map that powers both
 * {@code textDocument/definition} (go-to-definition) and {@code workspace/symbol} search.</p>
 *
 * <h2>Scanning strategy</h2>
 * <p>For <b>RDF/XML</b> profile files (ENTSO-E RDFS format), declaration lines carry
 * {@code rdf:about="…#LocalName"} or {@code rdf:about="#LocalName"}.  These are matched
 * first and take priority over any subsequent reference to the same fragment.</p>
 * <p>For <b>Turtle</b> files, full-IRI subject triples carry {@code <…#LocalName>} as
 * the first token on the line, matched by the fragment fallback pattern.</p>
 */
final class DefinitionIndex {

    private static final Logger LOG = LoggerFactory.getLogger(DefinitionIndex.class);

    static final int MAX_SYMBOLS = 100;

    /** Matches {@code rdf:about="…#LocalName"} — the declaration form in RDF/XML. */
    private static final Pattern ABOUT_PATTERN =
            Pattern.compile("rdf:about=\"[^\"]*#([A-Za-z][A-Za-z0-9._-]*)\"");

    /**
     * Matches any IRI fragment occurrence: {@code #LocalName} followed by a delimiter.
     * Covers both RDF/XML ({@code "}) and Turtle ({@code >}) contexts as a fallback.
     */
    private static final Pattern FRAGMENT_PATTERN =
            Pattern.compile("#([A-Za-z][A-Za-z0-9._-]*)(?:[\"'>\\s;,])");

    private final Map<Node, Location> locations;

    private DefinitionIndex(Map<Node, Location> locations) {
        this.locations = Collections.unmodifiableMap(locations);
    }

    /** Returns the declaration {@link Location} for {@code term}, or empty if not indexed. */
    Optional<Location> locationOf(Node term) {
        return Optional.ofNullable(locations.get(term));
    }

    /**
     * Returns workspace symbols whose local name contains {@code query} (case-insensitive).
     * Results are capped at {@link #MAX_SYMBOLS} and sorted alphabetically by name.
     * Symbols without a known source location are omitted.
     */
    List<WorkspaceSymbol> findSymbols(String query, SchemaIndex index) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var result = new ArrayList<WorkspaceSymbol>();

        for (Node cls : index.allClasses()) addSymbol(cls, q, SymbolKind.Class, result);
        for (Node prop : index.allProperties()) addSymbol(prop, q, SymbolKind.Property, result);

        result.sort(Comparator.comparing(WorkspaceSymbol::getName));
        if (result.size() > MAX_SYMBOLS) return result.subList(0, MAX_SYMBOLS);
        return result;
    }

    private void addSymbol(Node term, String query, SymbolKind kind, List<WorkspaceSymbol> out) {
        if (!term.isURI()) return;
        String local = localName(term.getURI());
        if (!local.toLowerCase(Locale.ROOT).contains(query)) return;
        Location loc = locations.get(term);
        Either<Location, WorkspaceSymbolLocation> locEither = loc != null
                ? Either.forLeft(loc)
                : Either.forRight(new WorkspaceSymbolLocation(""));
        out.add(new WorkspaceSymbol(local, kind, locEither));
    }

    // ---- Factory ---------------------------------------------------------------------------

    /**
     * Scans all source files referenced by {@code sourcePaths} and builds the declaration map.
     * Each file is read once; the mapping is built in a single pass.
     *
     * @param index       the schema index providing all known classes and properties
     * @param sourcePaths map from profile version IRI to the file that declares it
     */
    static DefinitionIndex build(SchemaIndex index, Map<VersionIri, Path> sourcePaths) {
        if (sourcePaths.isEmpty()) return new DefinitionIndex(new LinkedHashMap<>());

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

        var locations = new LinkedHashMap<Node, Location>();

        for (Node cls : index.allClasses()) {
            if (!locations.containsKey(cls))
                findLocation(cls, index.findClass(cls), sourcePaths, fileFragments, locations);
        }
        for (Node prop : index.allProperties()) {
            if (!locations.containsKey(prop))
                findLocation(prop, index.findProperty(prop), sourcePaths, fileFragments, locations);
        }

        LOG.debug("DefinitionIndex: {} locations indexed from {} files",
                locations.size(), uniqueFiles.size());
        return new DefinitionIndex(locations);
    }

    private static void findLocation(
            Node term,
            List<VersionIri> profiles,
            Map<VersionIri, Path> sourcePaths,
            Map<Path, Map<String, Integer>> fileFragments,
            Map<Node, Location> out) {
        String local = localName(term.getURI());
        if (local.isEmpty()) return;
        for (VersionIri v : profiles) {
            Path file = sourcePaths.get(v);
            if (file == null) continue;
            Map<String, Integer> fragments = fileFragments.get(file);
            if (fragments == null) continue;
            Integer lineNo = fragments.get(local);
            if (lineNo != null) {
                String fileUri = file.toUri().toString();
                out.put(term, new Location(fileUri,
                        new Range(new Position(lineNo, 0), new Position(lineNo, 0))));
                return;
            }
        }
    }

    /**
     * Scans {@code file} and returns a map of fragment identifier → 0-based line number.
     *
     * <p>Declaration lines (containing {@code rdf:about="…#fragment"}) take priority over
     * any earlier reference to the same fragment.  This ensures go-to-definition lands on
     * the {@code <rdfs:Class>} or {@code <rdf:Property>} block, not on a {@code rdfs:domain}
     * reference to the same IRI.</p>
     */
    static Map<String, Integer> scanFragments(Path file) throws IOException {
        var references   = new LinkedHashMap<String, Integer>();
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

    static String localName(String iri) {
        int last = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        return last >= 0 ? iri.substring(last + 1) : iri;
    }
}
