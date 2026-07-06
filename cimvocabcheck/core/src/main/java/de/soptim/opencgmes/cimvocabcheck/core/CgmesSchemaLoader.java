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

package de.soptim.opencgmes.cimvocabcheck.core;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimxml.graph.CimNamespaceFactoryRegistry;
import de.soptim.opencgmes.cimxml.graph.CimProfile;
import de.soptim.opencgmes.cimxml.parser.RdfXmlParser;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistryStd;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience entry point for building a {@link SparqlValidationApi} (or the underlying {@link
 * RdfsSchemaIndex}) from RDFS schema files on disk.
 *
 * <p>Schema files in ENTSO-E RDFS format ({@code .rdf}), Turtle ({@code .ttl}), and OWL ({@code
 * .owl}) are accepted. They are parsed via the {@code cimxml} CIM profile registry, which handles
 * the CIM-specific {@code cims:dataType} / {@code cims:range} annotations that plain RDFS parsers
 * would miss.
 *
 * <p>Usage:
 *
 * <pre>
 * // Load every .rdf/.ttl/.owl file from a directory
 * SparqlValidationApi api = CgmesSchemaLoader.fromDirectory(Path.of("schemas")).load();
 *
 * // Load specific files
 * SparqlValidationApi api = CgmesSchemaLoader.fromFiles(eqPath, tpPath).load();
 *
 * // Obtain just the index (for wiring into custom validators)
 * RdfsSchemaIndex index = CgmesSchemaLoader.fromDirectory(schemasDir).loadIndex();
 * </pre>
 */
public final class CgmesSchemaLoader {

  private static final Logger LOG = LoggerFactory.getLogger(CgmesSchemaLoader.class);

  /**
   * Substring of {@code CimProfile.wrap}'s message when a graph declares a {@code cim} namespace
   * that has no {@link CimNamespaceFactoryRegistry} entry — used to classify an all-failed file
   * load as {@link SchemaLoadCode#UNRECOGNIZED_CIM_NAMESPACE} rather than a generic parse failure.
   */
  private static final String UNREGISTERED_NAMESPACE_MARKER = "No profile factory registered for";

  private final Path directory; // non-null when constructed via fromDirectory
  private final List<Path> files; // non-null when constructed via fromFiles

  private CgmesSchemaLoader(Path directory, List<Path> files) {
    this.directory = directory;
    this.files = files;
  }

  // ---- Factory methods -------------------------------------------------------------------

  /**
   * Loads all {@code .rdf}, {@code .ttl}, and {@code .owl} files found directly inside {@code dir}
   * (non-recursive, sorted alphabetically).
   *
   * @param dir directory to scan; must exist and be a directory
   */
  public static CgmesSchemaLoader fromDirectory(Path dir) {
    return new CgmesSchemaLoader(Objects.requireNonNull(dir, "dir"), null);
  }

  /**
   * Loads specific schema files.
   *
   * @param files one or more schema file paths; must be non-empty
   */
  public static CgmesSchemaLoader fromFiles(Path... files) {
    Objects.requireNonNull(files, "files");
    return new CgmesSchemaLoader(null, List.of(files));
  }

  /**
   * Loads specific schema files.
   *
   * @param files one or more schema file paths; must be non-empty
   */
  public static CgmesSchemaLoader fromFiles(Iterable<Path> files) {
    Objects.requireNonNull(files, "files");
    var list = new ArrayList<Path>();
    files.forEach(list::add);
    return new CgmesSchemaLoader(null, List.copyOf(list));
  }

  // ---- Loading ---------------------------------------------------------------------------

  /**
   * Carries a loaded {@link RdfsSchemaIndex} together with the {@link VersionIri} → source-file
   * mapping collected while parsing. Callers that need file-level navigation (go-to-definition,
   * workspace symbols) use {@link #sourcePaths()} to locate declarations in profile files.
   *
   * <p>{@link #skippedFiles()} lists files that could not be parsed. When it is non-empty, the
   * index was built from the remaining files only — callers should surface these as warnings.
   */
  public record LoadedIndex(
      RdfsSchemaIndex index, Map<VersionIri, Path> sourcePaths, List<String> skippedFiles) {}

  /**
   * Parses the configured files and returns the {@link RdfsSchemaIndex}.
   *
   * <p>Files that cannot be parsed are skipped with a warning rather than causing a hard failure.
   * The load only fails if no valid CIM profile can be registered at all.
   *
   * @throws SchemaLoadException if the directory does not exist, no schema files are found, or no
   *     CIM profiles could be registered after parsing
   */
  public RdfsSchemaIndex loadIndex() throws SchemaLoadException {
    return buildIndexAndSources(resolveFiles()).index();
  }

  /**
   * Parses the configured files and returns both the index and the {@link VersionIri} → file
   * mapping needed for source navigation.
   *
   * <p>Files that cannot be parsed are recorded in {@link LoadedIndex#skippedFiles()} rather than
   * causing a hard failure. The load only fails if no valid CIM profile can be registered.
   *
   * @throws SchemaLoadException see {@link #loadIndex()}
   */
  public LoadedIndex loadIndexWithSources() throws SchemaLoadException {
    return buildIndexAndSources(resolveFiles());
  }

  /**
   * Parses the configured files and returns a fully initialised {@link SparqlValidationApi}.
   *
   * @throws SchemaLoadException see {@link #loadIndex()}
   */
  public SparqlValidationApi load() throws SchemaLoadException {
    return new SparqlValidationApi(loadIndex());
  }

  // ---- Loading from in-memory graphs -----------------------------------------------------

  /**
   * Builds an {@link RdfsSchemaIndex} from in-memory profile graphs, e.g. one graph per named graph
   * fetched from a SPARQL endpoint where the CGMES schema is hosted.
   *
   * <p>Each graph is wrapped as a {@link CimProfile} and registered. Graphs that are not CIM
   * profiles (instance data, unrelated vocabularies) and duplicate profiles are skipped — the load
   * only fails if no profile can be registered at all.
   *
   * <p>Profile identification keys off the {@code cim} namespace prefix, which a graph fetched over
   * SPARQL frequently lacks. This method therefore re-asserts the {@code cim} prefix by detecting a
   * namespace registered in {@link CimNamespaceFactoryRegistry} among the graph's IRIs before
   * wrapping — this covers the CIM 16/17/18 built-ins as well as any custom namespace registered
   * via the {@code cimNamespaces} config setting.
   *
   * @throws SchemaLoadException if no CIM profile could be registered from any graph
   */
  public static RdfsSchemaIndex indexFromGraphs(Iterable<Graph> graphs) throws SchemaLoadException {
    var registry = new CimProfileRegistryStd();
    var unrecognizedNamespaces = new LinkedHashSet<String>();
    int total = 0;
    int skipped = 0;
    for (Graph graph : graphs) {
      total++;
      String cimNs = ensureCimPrefix(graph);
      try {
        CimProfile profile = CimProfile.wrap(graph);
        try {
          registry.register(profile);
        } catch (IllegalArgumentException dup) {
          LOG.debug("Skipping duplicate profile graph: {}", dup.getMessage());
        }
      } catch (Exception e) {
        skipped++;
        if (cimNs != null && !CimNamespaceFactoryRegistry.hasProfileFactory(cimNs)) {
          unrecognizedNamespaces.add(cimNs);
        }
        LOG.debug("Graph is not a CIM profile, skipping: {}", e.getMessage());
      }
    }
    if (registry.getRegisteredProfiles().isEmpty()) {
      throw noProfilesFoundException(total, unrecognizedNamespaces);
    }
    LOG.info(
        "Loaded {} profile(s) from {} graph(s) ({} non-profile graph(s) skipped).",
        registry.getRegisteredProfiles().size(),
        total,
        skipped);
    return RdfsSchemaIndex.fromCimRegistry(registry);
  }

  private static SchemaLoadException noProfilesFoundException(
      int total, Set<String> unrecognizedNamespaces) {
    if (!unrecognizedNamespaces.isEmpty()) {
      return new SchemaLoadException(
          "No CIM profiles could be loaded from the supplied graphs ("
              + total
              + " graph(s) examined) — found unrecognized 'cim' namespace(s) with no registered"
              + " CimProfile factory: "
              + String.join(", ", unrecognizedNamespaces)
              + ". Register a factory via CimNamespaceFactoryRegistry, or declare the namespace"
              + " in the 'cimNamespaces' setting of opencgmes.json.",
          SchemaLoadCode.UNRECOGNIZED_CIM_NAMESPACE);
    }
    return new SchemaLoadException(
        "No CIM profiles could be loaded from the supplied graphs ("
            + total
            + " graph(s) examined).",
        SchemaLoadCode.NO_CIM_PROFILES_FOUND);
  }

  /**
   * Ensures the graph carries a {@code cim} namespace prefix, which {@code CimProfile.wrap} relies
   * on to detect the CIM version. A no-op when the prefix is already present (e.g. graphs parsed
   * from RDF/XML files).
   *
   * @return the (possibly newly-asserted) {@code cim} namespace, or {@code null} if none could be
   *     found among the graph's IRIs
   */
  private static String ensureCimPrefix(Graph graph) {
    String existing = graph.getPrefixMapping().getNsPrefixURI("cim");
    if (existing != null) {
      return existing;
    }
    String ns = detectCimNamespace(graph);
    if (ns != null) {
      graph.getPrefixMapping().setNsPrefix("cim", ns);
    }
    return ns;
  }

  /**
   * Returns the first namespace registered in {@link CimNamespaceFactoryRegistry} found among the
   * graph's IRIs, or {@code null}. Covers the CIM 16/17/18 built-ins plus any custom namespace
   * registered via config.
   */
  private static String detectCimNamespace(Graph graph) {
    Set<String> knownNamespaces = CimNamespaceFactoryRegistry.registeredNamespaces();
    var it = graph.find();
    try {
      while (it.hasNext()) {
        Triple t = it.next();
        for (Node n : new Node[] {t.getSubject(), t.getPredicate(), t.getObject()}) {
          if (n.isURI()) {
            String uri = n.getURI();
            for (String ns : knownNamespaces) {
              if (uri.startsWith(ns)) {
                return ns;
              }
            }
          }
        }
      }
    } finally {
      it.close();
    }
    return null;
  }

  // ---- Private ---------------------------------------------------------------------------

  private List<Path> resolveFiles() throws SchemaLoadException {
    if (directory != null) {
      if (!Files.isDirectory(directory)) {
        throw new SchemaLoadException(
            "Directory does not exist or is not a directory: " + directory);
      }
      try (Stream<Path> entries = Files.list(directory)) {
        var found =
            entries
                .map(Path::getFileName)
                .filter(Objects::nonNull)
                .filter(name -> isSchemaFile(name.toString()))
                .map(directory::resolve)
                .sorted()
                .collect(Collectors.toList());
        if (found.isEmpty()) {
          throw new SchemaLoadException("No .rdf/.ttl/.owl files found in directory: " + directory);
        }
        return found;
      } catch (SchemaLoadException rethrow) {
        throw rethrow;
      } catch (IOException e) {
        throw new SchemaLoadException(
            "Cannot list directory " + directory + ": " + e.getMessage(), e);
      }
    }
    if (files.isEmpty()) {
      throw new SchemaLoadException("No schema files specified.");
    }
    return files;
  }

  private static boolean isSchemaFile(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".rdf") || lower.endsWith(".ttl") || lower.endsWith(".owl");
  }

  private static LoadedIndex buildIndexAndSources(List<Path> filePaths) throws SchemaLoadException {
    var registry = new CimProfileRegistryStd();
    var parser = new RdfXmlParser();
    var failed = new ArrayList<String>();
    var sourcePaths = new LinkedHashMap<VersionIri, Path>();

    for (Path f : filePaths) {
      if (!Files.isRegularFile(f)) {
        throw new SchemaLoadException("Schema file does not exist: " + f);
      }
      try {
        CimProfile profile = parser.parseCimProfile(f);
        try {
          registry.register(profile);
          if (!profile.isHeaderProfile()) {
            for (Node iriNode : profile.getOwlVersionIris()) {
              sourcePaths.put(new VersionIri(iriNode), f);
            }
          }
          LOG.debug("Loaded schema: {}", f.getFileName());
        } catch (IllegalArgumentException dup) {
          // Duplicate version IRI — multiple files declare the same profile.
          // Skip the duplicate; the first registration wins.
          LOG.debug("Skipping {} — duplicate version IRI: {}", f.getFileName(), dup.getMessage());
        }
      } catch (Exception e) {
        failed.add(f + " (" + e.getMessage() + ")");
        LOG.warn("Failed to load {}: {}", f, e.getMessage());
      }
    }

    if (registry.getRegisteredProfiles().isEmpty()) {
      if (failed.isEmpty()) {
        throw new SchemaLoadException(
            "No CIM profiles were registered — check your schema files.",
            SchemaLoadCode.NO_CIM_PROFILES_FOUND);
      }
      String detail = "Failed to parse schema file(s):\n  " + String.join("\n  ", failed);
      SchemaLoadCode code =
          failed.stream().anyMatch(f -> f.contains(UNREGISTERED_NAMESPACE_MARKER))
              ? SchemaLoadCode.UNRECOGNIZED_CIM_NAMESPACE
              : SchemaLoadCode.NO_CIM_PROFILES_FOUND;
      throw new SchemaLoadException(detail, code);
    }
    if (!failed.isEmpty()) {
      LOG.warn(
          "Skipped {} unparseable schema file(s) — validation will use the remaining profiles:\n"
              + "  {}",
          failed.size(),
          String.join("\n  ", failed));
    }
    return new LoadedIndex(
        RdfsSchemaIndex.fromCimRegistry(registry),
        Collections.unmodifiableMap(sourcePaths),
        List.copyOf(failed));
  }

  // ---- Exception -------------------------------------------------------------------------

  /**
   * Thrown when schema loading fails due to a missing directory, unreadable file, or parse error.
   */
  public static final class SchemaLoadException extends Exception {
    private final SchemaLoadCode code;

    /** Creates an exception with the given message and no {@link SchemaLoadCode}. */
    public SchemaLoadException(String message) {
      this(message, (Throwable) null, null);
    }

    /** Creates an exception with the given message and cause, no {@link SchemaLoadCode}. */
    public SchemaLoadException(String message, Throwable cause) {
      this(message, cause, null);
    }

    /** Creates an exception with the given message and {@link SchemaLoadCode}. */
    public SchemaLoadException(String message, SchemaLoadCode code) {
      this(message, (Throwable) null, code);
    }

    private SchemaLoadException(String message, Throwable cause, SchemaLoadCode code) {
      super(message, cause);
      this.code = code;
    }

    /**
     * The stable classification of this failure, if known — e.g. {@link
     * SchemaLoadCode#UNRECOGNIZED_CIM_NAMESPACE} when a source declared a {@code cim} namespace
     * with no registered {@link de.soptim.opencgmes.cimxml.graph.CimProfile} factory. Empty for
     * failures unrelated to profile resolution (missing directory, unreadable file, etc.).
     */
    public Optional<SchemaLoadCode> code() {
      return Optional.ofNullable(code);
    }
  }
}
