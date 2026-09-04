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
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides go-to-definition for terms of a model held in RDFArchitect.
 *
 * <p>Such a term has no source file — it lives in a browser session's working copy — so there is
 * nothing for {@code textDocument/definition} to point at, and Ctrl+Click over it does nothing at
 * all: no underline, no message, no way to tell a term apart from an unknown word. This renders the
 * term as it stands in the loaded schema into a read-only Turtle document and points at that, the
 * same way a term hosted on a SPARQL endpoint is handled.
 *
 * <p>The first line names the instance, dataset and graph the term came from, in a form the editor
 * integrations parse: opening one of these documents is what makes them show the term in their
 * RDFArchitect view. That indirection is deliberate — both editors resolve a Ctrl+Click target
 * while the user is merely hovering, so opening the view can only hang off the navigation itself.
 */
final class RdfArchitectDefinitionPeek {

  private static final Logger LOG = LoggerFactory.getLogger(RdfArchitectDefinitionPeek.class);

  /** Marks the header line the editor integrations read to open the term in RDFArchitect. */
  static final String OPEN_MARKER = "#! rdfarchitect ";

  private static final String CIMS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";

  private final Path cacheDir;

  /**
   * The document last written to each path. Unlike an endpoint's, an RDFArchitect term is rendered
   * from the live schema on every request — a change made over there has to show up — so the
   * document cannot simply be cached. What can be skipped is rewriting an unchanged one, and both
   * editors resolve a Ctrl+Click target on hover as well as on click.
   */
  private final ConcurrentMap<Path, String> written = new ConcurrentHashMap<>();

  RdfArchitectDefinitionPeek() {
    this.cacheDir =
        Path.of(System.getProperty("java.io.tmpdir"), "cimvocabcheck-rdfarchitect-defs");
  }

  /**
   * Where a term lives in RDFArchitect: what the editor needs to show it.
   *
   * @param baseUrl the instance, or {@code null} when only the editor knows it
   * @param dataset the dataset holding the model, or {@code null} for a snapshot link
   * @param graph the graph holding this profile, or {@code null} when it is not known
   * @param profile how the profile reads, and what keeps two profiles' documents apart
   */
  record Target(String baseUrl, String dataset, String graph, String profile) {}

  /**
   * Writes (or reuses) the definition document for {@code term} as {@code profile} declares it, and
   * returns a location pointing at its declaration line.
   */
  Optional<Location> locationFor(Node term, SchemaIndex index, VersionIri profile, Target target) {
    try {
      String turtle = render(term, index, profile, target);
      Path file = write(term, turtle, target);
      int line = EndpointDefinitionPeek.subjectLine(turtle, term.getURI());
      return Optional.of(
          new Location(
              file.toUri().toString(), new Range(new Position(line, 0), new Position(line, 0))));
    } catch (Exception e) {
      LOG.warn("Could not write the definition of {}: {}", term.getURI(), e.getMessage());
      return Optional.empty();
    }
  }

  /** The document: a header the editor reads, then the term's own triples. */
  private static String render(Node term, SchemaIndex index, VersionIri profile, Target target) {
    List<VersionIri> scope = List.of(profile);
    Graph graph = GraphFactory.createDefaultGraph();
    describe(term, index, scope, graph);

    var out = new StringBuilder();
    out.append(OPEN_MARKER).append(openDirective(term, target)).append('\n');
    out.append("# ").append(EndpointDefinitionPeek.localName(term.getURI()));
    out.append(" — as the model in RDFArchitect declares it.\n");
    out.append("# profile: ").append(target.profile());
    if (target.graph() != null) {
      out.append("   graph: ").append(target.graph());
    }
    out.append('\n');
    if (target.dataset() != null) {
      out.append("# dataset: ").append(target.dataset()).append('\n');
    }
    out.append("#\n# Read-only: this is the schema as loaded for validation, rendered here.\n");
    out.append("# Edit the model in RDFArchitect; the change is picked up automatically.\n\n");
    out.append(EndpointDefinitionPeek.renderTurtle(graph, term.getURI()));
    return out.toString();
  }

  /**
   * The header the editors parse: {@code key=value} pairs, each value percent-encoded. Encoding
   * matters — a graph is named after the file it was imported from, and those have spaces in them.
   */
  private static String openDirective(Node term, Target target) {
    var out = new StringBuilder("class=").append(encode(term.getURI()));
    if (target.baseUrl() != null) {
      out.append(" base=").append(encode(target.baseUrl()));
    }
    if (target.dataset() != null) {
      out.append(" dataset=").append(encode(target.dataset()));
    }
    if (target.graph() != null) {
      out.append(" graph=").append(encode(target.graph()));
    }
    return out.toString();
  }

  /** Percent-encoding, with spaces as {@code %20} rather than {@code +} so both clients agree. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Adds everything the schema index knows about {@code term} to {@code graph}. */
  private static void describe(Node term, SchemaIndex index, List<VersionIri> scope, Graph graph) {
    boolean isClass = !index.findClass(term).isEmpty();
    boolean isProperty = !index.findProperty(term).isEmpty();

    if (isClass) {
      graph.add(Triple.create(term, RDF.type.asNode(), RDFS.Class.asNode()));
      for (Node parent : index.superClassesOf(term, scope)) {
        if (!parent.equals(term)) {
          graph.add(Triple.create(term, RDFS.subClassOf.asNode(), parent));
        }
      }
      for (Node member : index.enumMembersOf(term, scope)) {
        graph.add(Triple.create(member, RDF.type.asNode(), term));
      }
    }
    if (isProperty) {
      graph.add(Triple.create(term, RDF.type.asNode(), RDF.Property.asNode()));
      for (Node domain : index.domainsOf(term, scope)) {
        graph.add(Triple.create(term, RDFS.domain.asNode(), domain));
      }
      for (Node range : index.rangesOf(term, scope)) {
        graph.add(Triple.create(term, RDFS.range.asNode(), range));
      }
      index
          .multiplicityOf(term, scope)
          .ifPresent(
              m ->
                  graph.add(
                      Triple.create(
                          term,
                          NodeFactory.createURI(CIMS + "multiplicity"),
                          NodeFactory.createLiteralString(m.toString()))));
    }
    if (!isClass && !isProperty) {
      // An enumeration member: typed by the enumeration that declares it.
      for (Node enumClass : index.allClasses()) {
        if (index.enumMembersOf(enumClass, scope).contains(term)) {
          graph.add(Triple.create(term, RDF.type.asNode(), enumClass));
        }
      }
    }
    index
        .labelOf(term, scope)
        .ifPresent(
            label ->
                graph.add(
                    Triple.create(
                        term, RDFS.label.asNode(), NodeFactory.createLiteralString(label))));
    index
        .commentOf(term, scope)
        .ifPresent(
            comment ->
                graph.add(
                    Triple.create(
                        term, RDFS.comment.asNode(), NodeFactory.createLiteralString(comment))));
    if (isClass) {
      declaredProperties(term, index, scope, graph);
    }
  }

  /**
   * The attributes and associations declared on a class — what makes the document worth opening.
   */
  private static void declaredProperties(
      Node classTerm, SchemaIndex index, List<VersionIri> scope, Graph graph) {
    Set<Node> own = new LinkedHashSet<>();
    for (Node property : index.allProperties()) {
      if (index.domainsOf(property, scope).contains(classTerm)) {
        own.add(property);
      }
    }
    for (Node property : own) {
      graph.add(Triple.create(property, RDFS.domain.asNode(), classTerm));
      for (Node range : index.rangesOf(property, scope)) {
        graph.add(Triple.create(property, RDFS.range.asNode(), range));
      }
    }
  }

  /**
   * Writes the document, one directory per profile so the editor's chooser reads clearly — under
   * one per instance and dataset, so two of them never collide.
   *
   * <p>The header of this document is what navigates the editor, so a file shared between two
   * instances would send a stale tab to the wrong one. The instance is a directory rather than part
   * of the file name because editors show a document by its name and its immediate parent: the
   * profile is what the user needs to read there.
   */
  private Path write(Node term, String turtle, Target target) throws Exception {
    Path dir =
        cacheDir.resolve(originSlug(target)).resolve(EndpointDefinitionPeek.slug(target.profile()));
    Files.createDirectories(dir);
    String name =
        EndpointDefinitionPeek.localName(term.getURI())
            + "-"
            + Integer.toHexString(term.getURI().hashCode())
            + ".ttl";
    Path file = dir.resolve(name);
    // Serialised per path: two requests for the same term (the editors send them concurrently)
    // would otherwise race between the write and the read-only flag, and one of them would fail
    // or read a half-written document.
    written.compute(
        file,
        (path, previous) -> {
          if (turtle.equals(previous) && Files.isRegularFile(path)) {
            return previous;
          }
          try {
            path.toFile().setWritable(true);
            Files.write(path, turtle.getBytes(StandardCharsets.UTF_8));
            path.toFile().setReadOnly();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return turtle;
        });
    return file;
  }

  /** A readable, collision-free directory name for the instance and dataset a term came from. */
  private static String originSlug(Target target) {
    String host =
        target.baseUrl() == null ? "instance" : EndpointDefinitionPeek.hostOf(target.baseUrl());
    return EndpointDefinitionPeek.slug(host)
        + "-"
        + Integer.toHexString(Objects.hash(target.baseUrl(), target.dataset()));
  }
}
