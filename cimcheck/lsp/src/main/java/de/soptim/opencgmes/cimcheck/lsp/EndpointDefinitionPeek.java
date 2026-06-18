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

import de.soptim.opencgmes.cimcheck.core.schema.HttpSparqlGraphSource;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    this.cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "cimcheck-endpoint-defs");
  }

  /**
   * Returns a go-to-definition location for {@code termIri} hosted at {@code endpoint}, or empty if
   * the endpoint has no triples for it or the fetch fails. Only remote (http/https) endpoints are
   * supported; the result is cached per endpoint+term.
   */
  Optional<Location> locationFor(String endpoint, String termIri) {
    if (endpoint == null || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
      return Optional.empty();
    }
    String key = endpoint + "\n" + termIri;
    Location cached = cache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }

    try (HttpSparqlGraphSource source = new HttpSparqlGraphSource(endpoint, timeout)) {
      Graph graph = source.fetchResource(termIri);
      if (graph.isEmpty()) {
        LOG.debug("Endpoint {} has no triples defining {}", endpoint, termIri);
        return Optional.empty();
      }
      String turtle = renderTurtle(graph, termIri);
      Path file = writePeekFile(termIri, turtle);
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

  private Path writePeekFile(String termIri, String turtle) throws Exception {
    Files.createDirectories(cacheDir);
    String name = localName(termIri) + "-" + Integer.toHexString(termIri.hashCode()) + ".ttl";
    Path file = cacheDir.resolve(name);
    // Make writable to (re)write, then mark read-only — it is generated, not a real source.
    file.toFile().setWritable(true);
    Files.write(file, turtle.getBytes(StandardCharsets.UTF_8));
    file.toFile().setReadOnly();
    return file;
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
      try (var s = Files.list(cacheDir)) {
        return s.toList();
      }
    } catch (Exception e) {
      return List.of();
    }
  }
}
