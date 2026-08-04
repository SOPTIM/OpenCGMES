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

import de.soptim.opencgmes.cimvocabcheck.core.schema.HttpSparqlGraphSource;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides go-to-definition for schema terms that live on a remote SPARQL endpoint rather than in a
 * local RDFS file.
 *
 * <p>A CGMES schema loaded from a Fuseki endpoint has no source files to open, so this fetches the
 * term's triples from the endpoint ({@code CONSTRUCT} of every triple with the term as subject),
 * renders them as Turtle into a cached read-only file, and returns a {@link Location} pointing at
 * the term's declaration line. The result is a normal {@code file://} location, so it works
 * uniformly across LSP clients (VS Code, IntelliJ) and shows exactly the schema validation used.
 */
final class EndpointDefinitionPeek {

  private static final Logger LOG = LoggerFactory.getLogger(EndpointDefinitionPeek.class);

  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
  private static final String OWL = "http://www.w3.org/2002/07/owl#";
  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  private static final String CIMS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";

  private final Duration timeout;
  private final Path cacheDir;

  /** Cache of (endpoint + term IRI) → already-written peek location, stable for the session. */
  private final ConcurrentMap<String, Location> cache = new ConcurrentHashMap<>();

  EndpointDefinitionPeek(Duration timeout) {
    this.timeout = timeout;
    this.cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "cimvocabcheck-endpoint-defs");
  }

  /**
   * A profile a term is declared in, and the named graph holding that profile.
   *
   * @param label how the profile should read in the editor's chooser
   * @param graph the named graph to scope the fetch to
   */
  record ProfileGraph(String label, String graph) {}

  /**
   * Returns a go-to-definition location for {@code termIri} hosted at {@code endpoint}, or empty if
   * the endpoint has no triples for it or the fetch fails. Only remote (http/https) endpoints are
   * supported; the result is cached per endpoint+term.
   */
  Optional<Location> locationFor(String endpoint, String termIri) {
    return peek(endpoint, termIri, null, null);
  }

  /**
   * Returns one location per profile that declares {@code termIri} — the editor then offers the
   * choice — or a single merged peek when the profiles are unknown.
   *
   * <p>A CIM term is routinely declared in several profiles, and the union of their triples reads
   * as one contradictory definition: two {@code rdfs:domain}s, two multiplicities, no indication of
   * which profile said what. One document per profile keeps each one readable.
   */
  List<Location> locationsFor(String endpoint, String termIri, List<ProfileGraph> profiles) {
    if (profiles == null || profiles.isEmpty()) {
      return locationFor(endpoint, termIri).map(List::of).orElseGet(List::of);
    }
    var locations = new ArrayList<Location>();
    for (ProfileGraph profile : profiles) {
      peek(endpoint, termIri, profile.graph(), profile.label()).ifPresent(locations::add);
    }
    // Every per-profile fetch came back empty (a stale index, say) — the unscoped peek is still
    // better than reporting that the term has no definition at all.
    return locations.isEmpty()
        ? locationFor(endpoint, termIri).map(List::of).orElseGet(List::of)
        : List.copyOf(locations);
  }

  /**
   * Writes (or reuses) the peek document for one term, optionally scoped to one profile's graph.
   *
   * @param graphName the named graph to read, or {@code null} for every graph merged
   * @param label the profile's name, used to keep per-profile documents apart and to make the
   *     editor's chooser readable; {@code null} for the merged document
   */
  private Optional<Location> peek(String endpoint, String termIri, String graphName, String label) {
    if (endpoint == null || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
      return Optional.empty();
    }
    String key = endpoint + "\n" + termIri + "\n" + graphName;
    Location cached = cache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }

    try (HttpSparqlGraphSource source = new HttpSparqlGraphSource(endpoint, timeout)) {
      Graph graph = source.fetchResource(graphName, termIri);
      if (graph.isEmpty()) {
        LOG.debug("Endpoint {} has no triples defining {} in {}", endpoint, termIri, graphName);
        return Optional.empty();
      }
      String turtle = renderTurtle(graph, termIri);
      Path file = writePeekFile(termIri, turtle, label);
      int line = subjectLine(turtle, termIri);
      Location loc =
          new Location(
              file.toUri().toString(), new Range(new Position(line, 0), new Position(line, 0)));
      cache.put(key, loc);
      return Optional.of(loc);
    } catch (Exception e) {
      LOG.warn(
          "Could not build endpoint definition peek for {} at {}: {}",
          termIri,
          endpoint,
          e.getMessage());
      return Optional.empty();
    }
  }

  // ---- rendering / locating (package-visible for testing) --------------------------------

  /** Renders {@code graph} as pretty Turtle with the standard CGMES prefixes declared. */
  static String renderTurtle(Graph graph, String termIri) {
    Model model = ModelFactory.createModelForGraph(graph);
    model.setNsPrefix("rdf", RDF);
    model.setNsPrefix("rdfs", RDFS);
    model.setNsPrefix("owl", OWL);
    model.setNsPrefix("xsd", XSD);
    model.setNsPrefix("cims", CIMS);
    String ns = namespaceOf(termIri);
    if (ns != null && model.getNsURIPrefix(ns) == null) {
      model.setNsPrefix("cim", ns);
    }
    StringWriter sw = new StringWriter();
    RDFDataMgr.write(sw, model, RDFFormat.TURTLE_PRETTY);
    return sw.toString();
  }

  /**
   * Returns the 0-based line in {@code turtle} where {@code termIri} is declared as a subject — the
   * full {@code <iri>} if present, else a {@code prefix:LocalName} token — or 0 if not found.
   */
  static int subjectLine(String turtle, String termIri) {
    String full = "<" + termIri + ">";
    String local = localName(termIri);
    String[] lines = turtle.split("\n", -1);
    // Prefer the full-IRI subject form, then a prefixed local-name token at line start.
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(full)) {
        return i;
      }
    }
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].stripLeading();
      if (t.endsWith(":" + local) || t.contains(":" + local + " ")) {
        return i;
      }
    }
    return 0;
  }

  /**
   * Writes the peek document, putting a profile's copy in a directory named after it — editors show
   * the containing directory next to the file name, so that is what tells two profiles' documents
   * apart in the chooser.
   */
  private Path writePeekFile(String termIri, String turtle, String label) throws Exception {
    Path dir = label == null ? cacheDir : cacheDir.resolve(slug(label));
    Files.createDirectories(dir);
    String name = localName(termIri) + "-" + Integer.toHexString(termIri.hashCode()) + ".ttl";
    Path file = dir.resolve(name);
    // Make writable to (re)write, then mark read-only — it is generated, not a real source.
    file.toFile().setWritable(true);
    Files.write(file, turtle.getBytes(StandardCharsets.UTF_8));
    file.toFile().setReadOnly();
    return file;
  }

  /** A profile label as a directory name: readable, and safe on every filesystem. */
  static String slug(String label) {
    String cleaned = label.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("(^-+)|(-+$)", "");
    return cleaned.isEmpty() ? "profile" : cleaned;
  }

  static String namespaceOf(String iri) {
    int sep = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
    return sep >= 0 ? iri.substring(0, sep + 1) : null;
  }

  static String localName(String iri) {
    int sep = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
    return sep >= 0 ? iri.substring(sep + 1) : iri;
  }

  /** Exposes the cache directory for diagnostics/tests. */
  List<Path> cachedFiles() {
    try {
      if (!Files.isDirectory(cacheDir)) {
        return List.of();
      }
      try (var s = Files.walk(cacheDir)) {
        return s.filter(Files::isRegularFile).toList();
      }
    } catch (Exception e) {
      return List.of();
    }
  }
}
