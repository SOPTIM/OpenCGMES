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

import de.soptim.opencgmes.cimvocabcheck.core.CgmesSchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.DefaultPrefixes;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.config.CimvocabcheckConfig;
import de.soptim.opencgmes.cimvocabcheck.core.config.ConfigLoader;
import de.soptim.opencgmes.cimvocabcheck.core.schema.EndpointSchema;
import de.soptim.opencgmes.cimvocabcheck.core.schema.EndpointSchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfArchitectSchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfArchitectSource;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.FileGlobs;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookConfigLoader;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookConnection;
import de.soptim.opencgmes.cimvocabcheck.lsp.schema.SchemaLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.apache.jena.graph.Node;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages schema loading on a background thread.
 *
 * <p>Callers register {@code onLoaded} callbacks that fire each time a schema load (or reload)
 * succeeds — typically to re-trigger validation on all open documents.
 */
final class SchemaManager {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaManager.class);

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "schema-loader");
            t.setDaemon(true);
            return t;
          });

  /**
   * Separate pool for remote endpoint fetches. A remote load is a SELECT plus a CONSTRUCT per
   * profile graph, each with a {@link #REMOTE_TIMEOUT} timeout, so a slow or unreachable endpoint
   * can take a long time. Keeping it off the single {@link #executor} ensures a config reload
   * (opencgmes.jsonc edit) and other endpoint loads stay responsive instead of queueing behind it.
   */
  private final ExecutorService endpointExecutor =
      Executors.newFixedThreadPool(
          4,
          new ThreadFactory() {
            private final AtomicInteger threadNum = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "schema-endpoint-loader-" + threadNum.getAndIncrement());
              t.setDaemon(true);
              return t;
            }
          });

  /**
   * The <em>primary</em> workspace schema — the one discovered from the workspace root, or {@code
   * null} when no config (or a config without schemas) is found. It backs the workspace-global
   * operations that have no document context (workspace symbols, the explain command) and the
   * endpoint-schema builds.
   */
  private final AtomicReference<SparqlValidationApi> apiRef = new AtomicReference<>();

  private final AtomicReference<StrictnessLevel> levelRef =
      new AtomicReference<>(StrictnessLevel.DEFAULT);
  private final AtomicReference<DefinitionIndex> defRef = new AtomicReference<>();
  private final AtomicReference<Map<Node, Collection<VersionIri>>> namedGraphRef =
      new AtomicReference<>(Map.of());
  private final AtomicBoolean checkStdVocabRef = new AtomicBoolean(true);
  private final List<Runnable> onLoadedCallbacks = new CopyOnWriteArrayList<>();

  /**
   * Per-config-source schema cache for git-style nearest-config resolution: a document is validated
   * against the nearest {@code opencgmes.jsonc} above it. Keyed by resolved config-file path. When
   * no config is found, or a config declares no schemas, there is no workspace schema and the
   * document is validated syntax-only (there is no bundled default schema). The primary config's
   * entry is served from the {@code *Ref} fields above rather than this map; only <em>other</em>
   * configs found below the root land here. Cleared on every reload.
   */
  private final Map<String, WorkspaceSchema> workspaceSchemaCache = new ConcurrentHashMap<>();

  /** Cache key of the primary (workspace-root) config, set on each load. */
  private volatile String primaryConfigKey;

  /** Per-query timeout for fetching a schema from a remote SPARQL endpoint. */
  private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);

  /** How long a failed endpoint load is negatively cached before a retry is allowed. */
  private static final Duration FAILURE_TTL = Duration.ofSeconds(30);

  /**
   * Extensions a local {@code # [endpoint=...]} directive must have to be loaded as a schema.
   * Anything else (a CIMXML {@code .xml} model, {@code .nt}/{@code .nq}/{@code .trig} data) is
   * instance data for notebook execution, not a schema — see {@link #resolveSchema}.
   */
  private static final Set<String> SCHEMA_FILE_EXTENSIONS = Set.of("ttl", "rdf", "owl");

  /** Schemas loaded from a {@code # [endpoint=...]} directive, keyed by resolved source. */
  private final Map<String, ResolvedSchema> endpointCache = new ConcurrentHashMap<>();

  /**
   * Endpoint sources whose load failed, mapped to the {@link System#nanoTime()} after which a retry
   * is allowed. A negative cache avoids re-fetching every keystroke, but it expires after {@link
   * #FAILURE_TTL} so a transient outage doesn't disable the cell for the whole session.
   */
  private final Map<String, Long> failedEndpoints = new ConcurrentHashMap<>();

  /** Remote endpoints whose async load is in progress, so keystrokes don't resubmit it. */
  private final Set<String> inFlightEndpoints = ConcurrentHashMap.newKeySet();

  private volatile Path workspaceRoot;
  private final AtomicReference<LanguageClient> client = new AtomicReference<>();

  // ---- API -------------------------------------------------------------------------------

  void setClient(LanguageClient client) {
    this.client.set(client);
  }

  /** Registers a callback invoked (on the schema-loader thread) after each successful load. */
  void addOnLoadedCallback(Runnable callback) {
    onLoadedCallbacks.add(callback);
  }

  /** Starts an asynchronous schema load from the given workspace root. */
  void loadAsync(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
    executor.submit(() -> loadSync(workspaceRoot));
  }

  /** Triggers a reload using the previously-set workspace root. */
  void reloadAsync() {
    Path root = workspaceRoot;
    if (root != null) {
      executor.submit(() -> loadSync(root));
    }
  }

  /** Returns the loaded API, or empty if no schema has been successfully loaded yet. */
  Optional<SparqlValidationApi> getApi() {
    return Optional.ofNullable(apiRef.get());
  }

  /** Returns the strictness level from the last successfully loaded config. */
  StrictnessLevel strictnessLevel() {
    return levelRef.get();
  }

  /** Returns the definition index, or empty if the schema has not been loaded yet. */
  Optional<DefinitionIndex> getDefinitionIndex() {
    return Optional.ofNullable(defRef.get());
  }

  /**
   * Returns the per-graph profile scope derived from {@code namedGraphs} in the config, or an empty
   * map when no mapping is configured (in which case validation uses all profiles).
   */
  Map<Node, Collection<VersionIri>> namedGraphScope() {
    return namedGraphRef.get();
  }

  /**
   * Resolves the schema a document should be validated against — {@link #schemaSourceOf} followed
   * by {@link #resolveFrom}. Callers that also need to know <em>whether</em> the endpoint is a
   * schema source (to tell a failed endpoint apart from one that never was one) should call the two
   * separately.
   *
   * @param endpoint the {@code # [endpoint=...]} value, or {@code null} for the workspace schema
   * @param docDir the document's own directory, used for config discovery and relative endpoints
   */
  Optional<ResolvedSchema> resolveSchema(String endpoint, Path docDir) {
    return resolveSchema(endpoint == null ? List.of() : List.of(endpoint), docDir);
  }

  /** {@link #resolveSchema(String, Path)} over all of a document's directives. */
  Optional<ResolvedSchema> resolveSchema(List<String> endpoints, Path docDir) {
    return resolveFrom(schemaSourceOf(endpoints, docDir), docDir);
  }

  /** Single-directive form of {@link #schemaSourceOf(List, Path)}. */
  SchemaSource schemaSourceOf(String endpoint, Path docDir) {
    return schemaSourceOf(endpoint == null ? List.of() : List.of(endpoint), docDir);
  }

  /**
   * The source a document's directives load their schema from — a model held in a running
   * RDFArchitect (an {@link RdfArchitectDirective#SCHEME}-prefixed value), a remote SPARQL endpoint
   * URL, or a union of local {@code .ttl}/{@code .rdf}/{@code .owl} files (several directives and
   * glob patterns like {@code ./rdf/*.ttl} name multiple files) — or {@code null} when the
   * directives name no schema at all and the document's workspace schema applies instead. That is
   * the case for a blank directive, and for these notebook-specific ones:
   *
   * <ul>
   *   <li><b>Instance data.</b> The same directive is also the cell's execution target, which may
   *       be a CIMXML model or an {@code .nt}/{@code .nq}/{@code .trig} dump. Loading data as a
   *       schema would turn every term in the cell into a false diagnostic. In a multi-file union,
   *       instance-data files are skipped and only the schema files load.
   *   <li><b>An unresolvable connection name.</b> Names with no matching {@code "cimnotebook"}
   *       connection, or whose connection declares no remote URL, have no schema to offer.
   *   <li><b>Conflicting directives.</b> Several directives are meaningful only as a union of
   *       files; two URLs, two connection names, or a mix of kinds carry no single schema (the
   *       client refuses to execute such cells for the same reason).
   * </ul>
   */
  SchemaSource schemaSourceOf(List<String> endpoints, Path docDir) {
    List<String> directives =
        endpoints == null
            ? List.of()
            : endpoints.stream().filter(e -> e != null && !e.isBlank()).toList();
    if (directives.isEmpty()) {
      return null;
    }
    if (directives.size() == 1) {
      String endpoint = directives.get(0);
      if (endpoint.startsWith(RdfArchitectDirective.SCHEME)) {
        return SchemaSource.rdfArchitect(endpoint.substring(RdfArchitectDirective.SCHEME.length()));
      }
      if (isRemote(endpoint)) {
        return SchemaSource.remote(endpoint);
      }
      if (looksLikeConnectionName(endpoint)) {
        // Notebook cells may name a connection from the "cimnotebook" config section instead of a
        // URL or file. Resolving it here keeps validation and execution agreeing on what the
        // directive means: the schema loads from the connection's endpoint.
        NotebookConnection connection =
            NotebookConfigLoader.forDirectory(docDir).config().byName(endpoint);
        return connection != null && connection.url() != null && isRemote(connection.url())
            ? SchemaSource.remote(connection.url())
            : null;
      }
    } else if (!directives.stream().allMatch(SchemaManager::looksLikeFile)) {
      return null; // conflicting directives — see the javadoc
    }
    List<Path> schemaFiles = resolveSchemaFiles(directives, docDir);
    return schemaFiles.isEmpty() ? null : SchemaSource.localFiles(schemaFiles);
  }

  /**
   * Resolves file directives to the schema files they name: glob patterns expand to their matches,
   * plain paths resolve as-is (existence is checked at load time so a missing file still warns),
   * and non-schema files — instance data — are skipped.
   */
  private List<Path> resolveSchemaFiles(List<String> directives, Path docDir) {
    var files = new LinkedHashSet<Path>();
    for (String directive : directives) {
      if (FileGlobs.isPattern(directive)) {
        Path base = docDir != null ? docDir : workspaceRoot;
        try {
          FileGlobs.expand(directive, base).stream()
              .filter(SchemaManager::isSchemaFile)
              .forEach(files::add);
        } catch (PatternSyntaxException e) {
          LOG.debug("Ignoring invalid endpoint pattern {}: {}", directive, e.getMessage());
        }
        continue;
      }
      Path file = resolveLocalEndpoint(directive, docDir);
      if (isSchemaFile(file)) {
        files.add(file);
      } else {
        LOG.debug("Endpoint directive {} is not a schema file; using the workspace schema", file);
      }
    }
    return List.copyOf(files);
  }

  /**
   * Loads the schema from a {@link #schemaSourceOf} result, caching it by source; {@code null} —
   * i.e. no schema source — falls back to the document's workspace schema (the nearest {@code
   * opencgmes.jsonc} above it). Empty when nothing applies: no config, a config without schemas, or
   * an endpoint that is still loading or failed — the caller then validates syntax-only.
   */
  Optional<ResolvedSchema> resolveFrom(SchemaSource source, Path docDir) {
    if (source == null) {
      return workspaceSchemaFor(docDir).map(WorkspaceSchema::toResolvedSchema);
    }
    if (source.isRdfArchitect()) {
      String key = RdfArchitectDirective.SCHEME + source.rdfArchitect();
      return resolveAsync(key, () -> loadRdfArchitect(key));
    }
    return source.isRemote() ? resolveRemote(source.remoteUrl()) : resolveLocal(source.files());
  }

  /**
   * Whether a directive could be a named connection: no path separators, no extension dot, no glob
   * metacharacters. Only such directives pay for a config lookup; files virtually always have an
   * extension, so "model.xml" never does, while "local-fuseki" does. (A connection whose name
   * contains a dot or slash is not resolvable from a directive — documented limitation.)
   */
  private static boolean looksLikeConnectionName(String endpoint) {
    return !endpoint.contains("/")
        && !endpoint.contains("\\")
        && !endpoint.contains(".")
        && !FileGlobs.isPattern(endpoint);
  }

  /** Whether a directive is file-ish: not a remote URL and not a plausible connection name. */
  private static boolean looksLikeFile(String endpoint) {
    return !isRemote(endpoint) && !looksLikeConnectionName(endpoint);
  }

  /** Whether a local endpoint directive names a schema file (vs instance data — see above). */
  private static boolean isSchemaFile(Path file) {
    Path fileName = file.getFileName();
    String name = fileName != null ? fileName.toString() : "";
    int dot = name.lastIndexOf('.');
    String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    return SCHEMA_FILE_EXTENSIONS.contains(extension);
  }

  /**
   * Resolves the {@link WorkspaceSchema} for a document directory using git-style nearest-config
   * discovery: the nearest {@code opencgmes.jsonc} at or above {@code docDir} wins. Returns empty
   * when no config is found, the config declares no schemas, or the schema has not loaded / failed
   * to load — in all of which cases the caller validates syntax-only. The primary (workspace-root)
   * config is served from the cached primary schema; other configs are built lazily and cached.
   *
   * @param docDir the document's directory, or {@code null} to use the primary workspace schema
   */
  Optional<WorkspaceSchema> workspaceSchemaFor(Path docDir) {
    if (docDir == null) {
      if (primaryConfigKey == null) {
        return Optional.empty();
      }
      checkForEdits(configLiveKey(Path.of(primaryConfigKey)));
      return primarySchema();
    }
    Optional<Path> configFile = ConfigLoader.discoverFile(docDir);
    if (configFile.isEmpty()) { // no config → syntax-only
      return Optional.empty();
    }
    String key = configFile.get().toString();
    checkForEdits(configLiveKey(configFile.get()));
    if (key.equals(primaryConfigKey)) {
      return primarySchema();
    }
    WorkspaceSchema ws = workspaceSchemaCache.computeIfAbsent(key, this::buildForKey);
    return ws.api() == null ? Optional.empty() : Optional.of(ws);
  }

  /**
   * Result of {@link #schemaFilesFor(Path)}: the discovered config file and the schema files it
   * declares.
   */
  record SchemaFiles(Path configFile, List<Path> files) {}

  /**
   * Resolves the schema files the nearest {@code opencgmes.jsonc} declares for {@code docDir}
   * (falling back to the workspace root when {@code docDir} is {@code null}), without parsing them.
   * Empty when no config is found, the config declares no schemas, or resolution fails. Editor
   * integrations use this to hand the workspace schema to external tools (e.g. "Send Schema to
   * RDFArchitect").
   */
  Optional<SchemaFiles> schemaFilesFor(Path docDir) {
    Path start = docDir != null ? docDir : workspaceRoot;
    if (start == null) {
      return Optional.empty();
    }
    Optional<Path> configFile = ConfigLoader.discoverFile(start);
    if (configFile.isEmpty()) {
      return Optional.empty();
    }
    try {
      List<Path> files =
          SchemaLoader.resolveSchemaFiles(
              ConfigLoader.load(configFile.get()), configFile.get().toAbsolutePath().getParent());
      return files.isEmpty()
          ? Optional.empty()
          : Optional.of(new SchemaFiles(configFile.get(), files));
    } catch (Exception e) {
      LOG.warn("Could not resolve schema files for {}: {}", configFile.get(), e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Resolves the nearest config's {@code checkStandardVocabulary} flag for {@code docDir},
   * independent of whether a schema actually loaded. Used by the syntax-only fallback so a config's
   * {@code "standardVocabulary": "ignore"} is still honoured when no schema is available. Defaults
   * to {@code true} (checking enabled) when no config applies.
   */
  boolean checkStandardVocabularyFor(Path docDir) {
    if (docDir == null) {
      return checkStdVocabRef.get();
    }
    Optional<Path> configFile = ConfigLoader.discoverFile(docDir);
    if (configFile.isEmpty()) {
      return true; // no config → default (check enabled)
    }
    String key = configFile.get().toString();
    if (key.equals(primaryConfigKey)) {
      return checkStdVocabRef.get();
    }
    return workspaceSchemaCache.computeIfAbsent(key, this::buildForKey).checkStandardVocab();
  }

  /**
   * Synthesizes the primary workspace schema from the {@code *Ref} fields, or empty if unloaded.
   */
  private Optional<WorkspaceSchema> primarySchema() {
    SparqlValidationApi api = apiRef.get();
    if (api == null) {
      return Optional.empty();
    }
    return Optional.of(
        new WorkspaceSchema(
            api, levelRef.get(), defRef.get(), namedGraphRef.get(), checkStdVocabRef.get()));
  }

  /** Builds (and notifies on failure) the schema for a non-primary config key. */
  private WorkspaceSchema buildForKey(String key) {
    try {
      return buildSchemaForConfig(Path.of(key), false);
    } catch (Exception e) {
      LOG.error("Failed to load schema for {}: {}", key, e.getMessage(), e);
      notify(
          MessageType.Error,
          "CIMVocabCheck: schema load failed for " + key + " — " + e.getMessage());
      // Even when the schema fails to load, honour the config's standard-vocabulary flag so
      // the syntax-only fallback still respects "standardVocabulary": "ignore".
      return noSchemaWorkspace(readCheckStandardVocab(Path.of(key)));
    }
  }

  /** Best-effort read of a config's standard-vocabulary flag; defaults to {@code true} on error. */
  private static boolean readCheckStandardVocab(Path configFile) {
    try {
      return ConfigLoader.load(configFile).checkStandardVocabulary();
    } catch (Exception e) {
      return true;
    }
  }

  /** A {@link WorkspaceSchema} carrying no schema — documents fall back to a syntax-only check. */
  private static WorkspaceSchema noSchemaWorkspace() {
    return noSchemaWorkspace(true);
  }

  private static WorkspaceSchema noSchemaWorkspace(boolean checkStandardVocab) {
    return new WorkspaceSchema(null, StrictnessLevel.DEFAULT, null, Map.of(), checkStandardVocab);
  }

  private static boolean isRemote(String endpoint) {
    return endpoint.startsWith("http://") || endpoint.startsWith("https://");
  }

  private Path resolveLocalEndpoint(String endpoint, Path docDir) {
    Path p = Path.of(endpoint);
    if (p.isAbsolute()) {
      return p.normalize();
    }
    Path base = docDir != null ? docDir : workspaceRoot;
    return base != null ? base.resolve(endpoint).normalize() : p.normalize();
  }

  /** Loads a schema from local files synchronously (fast); caches success and failure. */
  private Optional<ResolvedSchema> resolveLocal(List<Path> files) {
    String key = files.stream().map(Path::toString).collect(Collectors.joining("\n"));
    ResolvedSchema cached = endpointCache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    if (isFailed(key)) {
      return Optional.empty();
    }
    try {
      List<Path> missing = files.stream().filter(f -> !Files.isRegularFile(f)).toList();
      if (!missing.isEmpty()) {
        fail(
            key,
            MessageType.Warning,
            "CIMVocabCheck: endpoint schema file not found: " + describeFiles(missing));
        return Optional.empty();
      }
      // loadIndexWithSources (rather than loadIndex) keeps the VersionIri → Path mapping so a
      // DefinitionIndex can be built below — go-to-definition on a local-file endpoint must jump
      // into that real file, not fall back to the remote-only EndpointDefinitionPeek.
      var loaded = CgmesSchemaLoader.fromFiles(files).loadIndexWithSources();
      var defIndex = DefinitionIndex.build(loaded.index(), loaded.sourcePaths());
      ResolvedSchema schema = buildSchema(loaded.index(), Map.of(), defIndex);
      endpointCache.put(key, schema);
      LOG.info("Loaded endpoint schema from {}", describeFiles(files));
      return Optional.of(schema);
    } catch (Exception e) {
      fail(
          key,
          MessageType.Error,
          "CIMVocabCheck: failed to load schema from "
              + describeFiles(files)
              + " — "
              + e.getMessage());
      return Optional.empty();
    }
  }

  private static String describeFiles(List<Path> files) {
    return files.stream().map(Path::toString).collect(Collectors.joining(", "));
  }

  /**
   * Resolves a schema hosted on a remote SPARQL endpoint. The fetch (one enumeration query plus a
   * CONSTRUCT per profile graph) runs on the dedicated {@link #endpointExecutor} so neither the
   * validator thread nor workspace reloads block on it; open documents are revalidated once the
   * schema lands. Returns empty until then.
   */
  private Optional<ResolvedSchema> resolveRemote(String endpoint) {
    return resolveAsync(endpoint, () -> loadRemoteEndpoint(endpoint));
  }

  /**
   * Serves a network-backed schema from the cache, kicking off {@code load} the first time it is
   * asked for. Returns empty until the load lands (open documents are revalidated then), and stays
   * empty for a failure window afterwards so a keystroke cannot re-trigger a failing fetch.
   */
  private Optional<ResolvedSchema> resolveAsync(String key, Runnable load) {
    ResolvedSchema cached = endpointCache.get(key);
    if (cached != null) {
      checkForEdits(key);
      return Optional.of(cached);
    }
    if (isFailed(key)) {
      return Optional.empty();
    }
    if (inFlightEndpoints.add(key)) {
      notify(MessageType.Info, "CIMVocabCheck: loading schema from " + describeSource(key) + " …");
      endpointExecutor.submit(load);
    }
    return Optional.empty();
  }

  /** Loads the schema for a {@code # [rdfarchitect=...]} document. */
  private void loadRdfArchitect(String key) {
    String url = rdfArchitectRefOf(key);
    RdfArchitectConnection connection = rdfArchitect.get();
    try {
      RdfArchitectSource source =
          RdfArchitectSource.parse(url, connection == null ? null : connection.url());
      String stamp = liveStampOf(source, connection);
      EndpointSchema es =
          RdfArchitectSchemaLoader.load(
              source, REMOTE_TIMEOUT, connection == null ? null : connection.sessionId());
      if (!es.hasSchema()) {
        markFailed(key);
        notify(
            MessageType.Warning,
            "CIMVocabCheck: RDFArchitect "
                + source.describe()
                + " "
                + describeNoSchema(es)
                + " — validating SPARQL syntax only.");
        return;
      }
      endpointCache.put(key, buildSchema(es.index(), es.namedGraphScope(), null));
      rememberLiveSource(key, source, connection, stamp, () -> refetchDirectiveSchema(key));
      LOG.info(
          "Loaded schema from RDFArchitect {} ({} schema graph(s))",
          source.describe(),
          es.schemaGraphNames().size());
      notify(
          MessageType.Info,
          "CIMVocabCheck: schema loaded from RDFArchitect "
              + source.describe()
              + " — "
              + es.schemaGraphNames().size()
              + " schema graph(s).");
      fireOnLoaded();
    } catch (RuntimeException e) {
      LOG.warn("Failed to load schema from RDFArchitect {}: {}", url, e.getMessage());
      fail(
          key,
          MessageType.Error,
          "CIMVocabCheck: could not load the schema from RDFArchitect "
              + url
              + " — "
              + e.getMessage());
    } finally {
      inFlightEndpoints.remove(key);
    }
  }

  // ---- Live RDFArchitect datasets ----------------------------------------------------------

  /**
   * The RDFArchitect window an editor has connected, if any.
   *
   * <p>Datasets live in a browser session, so reading the model as it is being edited means
   * borrowing that session: the embedded app hands its session id to the editor, the editor hands
   * it here, and schema fetches carry it. Without a connection only snapshots and full URLs resolve
   * — a bare dataset name has no instance to look in.
   *
   * @param url the instance's base URL
   * @param sessionId the value of that window's session cookie
   */
  record RdfArchitectConnection(String url, String sessionId) {}

  private final AtomicReference<RdfArchitectConnection> rdfArchitect = new AtomicReference<>();

  /** Live sources behind cached schemas, so edits in RDFArchitect can be noticed. */
  private final Map<String, LiveSource> liveSources = new ConcurrentHashMap<>();

  /**
   * How long a live dataset is served from cache before its change log is checked again. Long
   * enough that a burst of keystrokes does not poll RDFArchitect, short enough that an edit made
   * over there shows up while the user is still looking at the query. Shortened by tests.
   */
  static volatile Duration liveCheckInterval = Duration.ofSeconds(3);

  /**
   * A cached schema read from a live dataset, with the change stamp it had when it was read and the
   * earliest time to look for edits again.
   */
  private static final class LiveSource {
    private final RdfArchitectSource source;
    private final RdfArchitectConnection connection;
    private final Runnable onChanged;
    private volatile String stamp;
    private final AtomicLong nextCheckAt = new AtomicLong();

    LiveSource(
        RdfArchitectSource source,
        RdfArchitectConnection connection,
        String stamp,
        Runnable onChanged) {
      this.source = source;
      this.connection = connection;
      this.onChanged = onChanged;
      this.stamp = stamp;
      this.nextCheckAt.set(System.nanoTime() + liveCheckInterval.toNanos());
    }

    /** Whether it is time to look for edits again; claims the check when it is. */
    boolean claimCheck() {
      long due = nextCheckAt.get();
      return due - System.nanoTime() <= 0
          && nextCheckAt.compareAndSet(due, System.nanoTime() + liveCheckInterval.toNanos());
    }
  }

  /**
   * Connects (or, with a {@code null} session, disconnects) the RDFArchitect window an editor is
   * showing. Everything read from RDFArchitect is dropped, since a different session sees different
   * datasets, and open documents are revalidated against the new connection.
   */
  void connectRdfArchitect(String url, String sessionId) {
    RdfArchitectConnection connection =
        url == null || url.isBlank() || sessionId == null || sessionId.isBlank()
            ? null
            : new RdfArchitectConnection(stripTrailingSlash(url.trim()), sessionId.trim());
    RdfArchitectConnection previous = rdfArchitect.getAndSet(connection);
    if (Objects.equals(previous, connection)) {
      return;
    }
    forgetRdfArchitectSchemas();
    LOG.info(
        "RDFArchitect connection {}",
        connection == null ? "cleared" : "set to " + connection.url());
    reloadAsync();
    fireOnLoaded();
  }

  /** The connected instance's URL, or empty when no editor has connected one. */
  Optional<String> connectedRdfArchitect() {
    return Optional.ofNullable(rdfArchitect.get()).map(RdfArchitectConnection::url);
  }

  /** Drops everything read from RDFArchitect: another session holds different datasets. */
  private void forgetRdfArchitectSchemas() {
    liveSources.clear();
    endpointCache.keySet().removeIf(key -> key.startsWith(RdfArchitectDirective.SCHEME));
    failedEndpoints.keySet().removeIf(key -> key.startsWith(RdfArchitectDirective.SCHEME));
    workspaceSchemaCache.clear();
  }

  /**
   * The change stamp of a live source, read <em>before</em> its content is fetched.
   *
   * <p>Order matters: a stamp taken after the fetch would already include an edit made while the
   * fetch was running, and that edit would then never be noticed. Taken before, such an edit merely
   * costs one redundant refetch.
   *
   * @return the stamp, or {@code null} for a snapshot — it is immutable, so there is nothing to
   *     re-check
   */
  private String liveStampOf(RdfArchitectSource source, RdfArchitectConnection connection) {
    return source.snapshot() != null
        ? null
        : RdfArchitectSchemaLoader.changeStamp(
            source, REMOTE_TIMEOUT, connection == null ? null : connection.sessionId());
  }

  /**
   * Records what a cached RDFArchitect schema was read from, so edits to it can be noticed.
   *
   * @param stamp the change stamp from {@link #liveStampOf} taken before the content was read
   * @param onChanged rebuilds that schema — refetching the one document source for a directive,
   *     reloading the workspace for a config
   */
  private void rememberLiveSource(
      String key,
      RdfArchitectSource source,
      RdfArchitectConnection connection,
      String stamp,
      Runnable onChanged) {
    if (stamp == null) {
      liveSources.remove(key);
      return;
    }
    liveSources.put(key, new LiveSource(source, connection, stamp, onChanged));
  }

  /**
   * Notices edits made in RDFArchitect since a live schema was read, without blocking the caller:
   * the change log is polled at most every {@link #liveCheckInterval}, and only a stamp that
   * actually moved triggers a refetch (and then a revalidation of the open documents).
   */
  private void checkForEdits(String key) {
    LiveSource live = liveSources.get(key);
    if (live == null || !live.claimCheck()) {
      return;
    }
    endpointExecutor.submit(
        () -> {
          String stamp =
              RdfArchitectSchemaLoader.changeStamp(
                  live.source,
                  REMOTE_TIMEOUT,
                  live.connection == null ? null : live.connection.sessionId());
          if (stamp == null || stamp.equals(live.stamp)) {
            return;
          }
          LOG.info("RDFArchitect {} changed — reloading the schema", live.source.describe());
          live.stamp = stamp;
          live.onChanged.run();
        });
  }

  /** Cache key for the live source behind a config's schema. */
  private static String configLiveKey(Path configFile) {
    return "config:" + configFile;
  }

  /**
   * Reloads the workspace schema without announcing it. A live RDFArchitect dataset reloads
   * whenever someone edits the model, and a toast per edit would be noise rather than news.
   */
  private void reloadQuietlyAsync() {
    Path root = workspaceRoot;
    if (root != null) {
      executor.submit(() -> loadSync(root, true));
    }
  }

  /** Re-reads the schema of a {@code # [rdfarchitect=...]} document after an edit over there. */
  private void refetchDirectiveSchema(String key) {
    endpointCache.remove(key);
    if (inFlightEndpoints.add(key)) {
      loadRdfArchitect(key);
    }
  }

  /** The reference behind an {@code rdfarchitect:} cache key. */
  private static String rdfArchitectRefOf(String key) {
    return key.substring(RdfArchitectDirective.SCHEME.length());
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  /** How a schema source reads in a user-facing message. */
  private static String describeSource(String key) {
    return key.startsWith(RdfArchitectDirective.SCHEME)
        ? "RDFArchitect " + key.substring(RdfArchitectDirective.SCHEME.length())
        : "endpoint " + key;
  }

  private void loadRemoteEndpoint(String endpoint) {
    try {
      EndpointSchema es = EndpointSchemaLoader.loadFromEndpoint(endpoint, REMOTE_TIMEOUT);
      if (!es.hasSchema()) {
        // Reachable, but no CIM schema graphs to validate against. Negatively cache it so we
        // don't re-fetch on every keystroke; the document falls back to syntax-only checking.
        markFailed(endpoint);
        notify(
            MessageType.Warning,
            "CIMVocabCheck: endpoint "
                + endpoint
                + " "
                + describeNoSchema(es)
                + " — validating SPARQL syntax only.");
        return;
      }
      ResolvedSchema schema = buildSchema(es.index(), es.namedGraphScope(), null);
      endpointCache.put(endpoint, schema);
      LOG.info(
          "Loaded schema from endpoint {} ({} instance graph(s) auto-mapped, {} unmatched, {}"
              + " schema graph(s))",
          endpoint,
          es.instanceGraphsMapped(),
          es.unmatchedGraphs().size(),
          es.schemaGraphNames().size());
      notify(
          MessageType.Info,
          "CIMVocabCheck: schema loaded from endpoint "
              + endpoint
              + " — "
              + es.instanceGraphsMapped()
              + " instance graph(s) auto-mapped to profiles, "
              + es.schemaGraphNames().size()
              + " schema graph(s) detected.");
      if (!es.unmatchedGraphs().isEmpty()) {
        notify(
            MessageType.Warning,
            "CIMVocabCheck: could not auto-detect a CGMES profile for "
                + es.unmatchedGraphs().size()
                + " named graph(s); terms in "
                + (es.unmatchedGraphs().size() == 1 ? "it" : "them")
                + " will be reported as unknown. Graph(s): "
                + describeGraphs(es.unmatchedGraphs()));
      }
      fireOnLoaded(); // revalidate open documents so the cell gets its diagnostics
    } catch (Exception e) {
      LOG.error("Failed to load schema from endpoint {}: {}", endpoint, e.getMessage(), e);
      markFailed(endpoint);
      notify(
          MessageType.Error,
          "CIMVocabCheck: failed to load schema from endpoint "
              + endpoint
              + " — "
              + e.getMessage());
    } finally {
      inFlightEndpoints.remove(endpoint);
    }
  }

  /**
   * Describes why an endpoint yielded no schema, distinguishing "no schema-like graphs at all" from
   * "schema graphs were found but none resolved to a registered CIM profile" (most commonly an
   * unrecognized {@code cim} namespace — see the {@code cimNamespaces} setting in {@code
   * opencgmes.jsonc}).
   */
  private static String describeNoSchema(EndpointSchema es) {
    if (es.schemaGraphNames().isEmpty()) {
      return "exposes no CIM schema graphs";
    }
    return "exposes "
        + es.schemaGraphNames().size()
        + " schema graph(s), but none resolved to a registered CIM profile"
        + (es.unresolvedReason() != null ? " — " + es.unresolvedReason() : "");
  }

  /** Renders up to a few graph names for a warning message, eliding the rest. */
  private static String describeGraphs(List<Node> graphs) {
    int shown = Math.min(graphs.size(), 3);
    var sb = new StringBuilder();
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append('<').append(graphs.get(i).getURI()).append('>');
    }
    if (graphs.size() > shown) {
      sb.append(", … (").append(graphs.size() - shown).append(" more)");
    }
    return sb.toString();
  }

  /**
   * Builds a {@link ResolvedSchema} from an index, using default prefixes and the given scope.
   *
   * @param definitionIndex go-to-definition source index, or {@code null} when {@code index} has no
   *     backing source file (a remote SPARQL endpoint schema).
   */
  private ResolvedSchema buildSchema(
      RdfsSchemaIndex index,
      Map<Node, Collection<VersionIri>> scope,
      DefinitionIndex definitionIndex) {
    var prefixes = DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, index);
    var api = new SparqlValidationApi(index, prefixes, checkStdVocabRef.get());
    return new ResolvedSchema(api, levelRef.get(), scope, definitionIndex);
  }

  /** Records an endpoint as failed (negative cache) and notifies once per failure window. */
  private void fail(String key, MessageType type, String message) {
    if (markFailed(key)) {
      notify(type, message);
    }
  }

  /**
   * Records {@code key} as failed until {@link #FAILURE_TTL} elapses.
   *
   * @return {@code true} if this opens a fresh failure window (no live entry was present), so the
   *     caller should notify; {@code false} if a still-valid failure was already recorded.
   */
  private boolean markFailed(String key) {
    long expiry = System.nanoTime() + FAILURE_TTL.toNanos();
    Long prev = failedEndpoints.put(key, expiry);
    return prev == null || prev - System.nanoTime() <= 0;
  }

  /**
   * Returns whether {@code key}'s last failure is still within {@link #FAILURE_TTL}. An expired
   * entry is evicted so the next {@code resolveSchema} retries the load.
   */
  private boolean isFailed(String key) {
    Long expiry = failedEndpoints.get(key);
    if (expiry == null) {
      return false;
    }
    if (expiry - System.nanoTime() > 0) {
      return true;
    }
    failedEndpoints.remove(key, expiry);
    return false;
  }

  void shutdown() {
    executor.shutdown();
    endpointExecutor.shutdown();
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
      if (!endpointExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
        endpointExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      endpointExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  // ---- Private ---------------------------------------------------------------------------

  private void loadSync(Path root) {
    loadSync(root, false);
  }

  private void loadSync(Path root, boolean quiet) {
    // Endpoint schemas and per-config schemas are cached for the session; drop them on reload so
    // a strictness change propagates and any transient load failures get retried.
    endpointCache.clear();
    failedEndpoints.clear();
    workspaceSchemaCache.clear();

    Optional<Path> configFile = ConfigLoader.discoverFile(root);
    primaryConfigKey = configFile.map(Path::toString).orElse(null);
    try {
      WorkspaceSchema primary =
          configFile.isPresent()
              ? buildSchemaForConfig(configFile.get(), quiet)
              : noSchemaWorkspace();
      apiRef.set(primary.api());
      levelRef.set(primary.level());
      defRef.set(primary.definitionIndex());
      namedGraphRef.set(primary.namedGraphScope());
      checkStdVocabRef.set(primary.checkStandardVocab());

      if (primary.api() == null) {
        LOG.info(
            "No schema configured under {} — syntax-only unless a # [endpoint=...] is used", root);
        if (!quiet) {
          notify(
              MessageType.Info,
              "CIMVocabCheck: no schema configured — checking SPARQL/SHACL syntax only. Add"
                  + " \"schemas\" to opencgmes.jsonc, or a \"# [endpoint=...]\" directive, for"
                  + " schema-based validation.");
        }
      } else {
        LOG.info(
            "Schema loaded successfully from {} (strictness: {})",
            configFile.get(),
            primary.level());
        if (!quiet) {
          notify(MessageType.Info, "CIMVocabCheck: schema loaded successfully.");
        }
      }
      fireOnLoaded();
    } catch (Exception e) {
      LOG.error("Failed to load schema: {}", e.getMessage(), e);
      notify(MessageType.Error, "CIMVocabCheck: schema load failed — " + e.getMessage());
      apiRef.set(null);
      defRef.set(null);
      namedGraphRef.set(Map.of());
      // Preserve the config's standard-vocabulary flag so the syntax-only fallback honours it.
      checkStdVocabRef.set(configFile.map(SchemaManager::readCheckStandardVocab).orElse(true));
    }
  }

  /**
   * Builds a {@link WorkspaceSchema} from a discovered config file. When the config declares no
   * {@code schemas}/{@code schemasDirectory}, the result carries no schema (a syntax-only context);
   * there is no bundled default. Config-relative schema paths resolve against the config's
   * directory.
   */
  private WorkspaceSchema buildSchemaForConfig(Path configFile, boolean quietLoad)
      throws Exception {
    Path base = configFile.toAbsolutePath().getParent();
    CimvocabcheckConfig config = ConfigLoader.load(configFile);
    if (config.rdfArchitect() != null && !config.rdfArchitect().isBlank()) {
      return buildSchemaFromRdfArchitect(config, configFile, quietLoad);
    }
    Optional<SchemaLoader.SchemaAndSources> loaded = SchemaLoader.loadWithSources(config, base);
    if (loaded.isEmpty()) {
      // Config present but no schemas declared → syntax-only (unless documents use an endpoint).
      return new WorkspaceSchema(
          null, parseLevel(config), null, Map.of(), config.checkStandardVocabulary());
    }
    return assemble(config, loaded.get());
  }

  /**
   * Builds a {@link WorkspaceSchema} from the profiles held in an RDFArchitect instance ({@code
   * "rdfArchitect"} in the config) instead of from schema files, so a workspace validates against
   * the model as it is curated there.
   *
   * <p>This is a network load on the schema-loading thread, like the primary file load. A bare
   * dataset name is read from the connected RDFArchitect window (see {@link #connectRdfArchitect}),
   * so the workspace validates against the model as it is being edited; connecting or disconnecting
   * rebuilds it. There is no definition index — the terms have no backing source file to jump to.
   */
  private WorkspaceSchema buildSchemaFromRdfArchitect(
      CimvocabcheckConfig config, Path configFile, boolean quietLoad) {
    RdfArchitectConnection connection = rdfArchitect.get();
    RdfArchitectSource source;
    try {
      source =
          RdfArchitectSource.parse(
              config.rdfArchitect(), connection == null ? null : connection.url());
    } catch (IllegalArgumentException e) {
      // Typically: the config names a dataset but no editor has connected a window yet. Say so
      // instead of silently validating against nothing.
      notify(MessageType.Warning, "CIMVocabCheck: " + e.getMessage());
      return noSchemaWorkspace(config.checkStandardVocabulary());
    }
    String stamp = liveStampOf(source, connection);
    EndpointSchema es =
        RdfArchitectSchemaLoader.load(
            source, REMOTE_TIMEOUT, connection == null ? null : connection.sessionId());
    if (!es.hasSchema()) {
      notify(
          MessageType.Warning,
          "CIMVocabCheck: RDFArchitect "
              + source.describe()
              + " "
              + describeNoSchema(es)
              + " — validating SPARQL syntax only.");
      return noSchemaWorkspace(config.checkStandardVocabulary());
    }
    rememberLiveSource(
        configLiveKey(configFile), source, connection, stamp, this::reloadQuietlyAsync);
    LOG.info(
        "Loaded schema from RDFArchitect {} ({} schema graph(s))",
        source.describe(),
        es.schemaGraphNames().size());
    if (!quietLoad) {
      notify(
          MessageType.Info,
          "CIMVocabCheck: schema loaded from RDFArchitect "
              + source.describe()
              + " — "
              + es.schemaGraphNames().size()
              + " schema graph(s).");
    }
    var prefixes =
        config.prefixes() != null
            ? config.prefixes()
            : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, es.index());
    boolean checkStd = config.checkStandardVocabulary();
    var scope =
        config.hasNamedGraphs()
            ? SparqlValidationApi.buildNamedGraphScope(
                config.namedGraphs(), es.index(), msg -> LOG.warn("{}", msg))
            : es.namedGraphScope();
    return new WorkspaceSchema(
        new SparqlValidationApi(es.index(), prefixes, checkStd),
        parseLevel(config),
        null,
        scope,
        checkStd);
  }

  /**
   * Assembles the API, strictness, definition index, and named-graph scope from a config + schema.
   */
  private WorkspaceSchema assemble(
      CimvocabcheckConfig config, SchemaLoader.SchemaAndSources loaded) {
    var prefixes =
        config.prefixes() != null
            ? config.prefixes()
            : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, loaded.index());
    boolean checkStd = config.checkStandardVocabulary();
    var api = new SparqlValidationApi(loaded.index(), prefixes, checkStd);
    var level = parseLevel(config);
    var defIndex = DefinitionIndex.build(loaded.index(), loaded.sourcePaths());
    var scope =
        SparqlValidationApi.buildNamedGraphScope(
            config.namedGraphs(), loaded.index(), msg -> LOG.warn("{}", msg));
    if (!loaded.skippedFiles().isEmpty()) {
      notify(
          MessageType.Warning,
          "CIMVocabCheck: schema loaded with warnings — "
              + loaded.skippedFiles().size()
              + " file(s) could not be parsed and were skipped:\n"
              + String.join("\n", loaded.skippedFiles()));
    }
    return new WorkspaceSchema(api, level, defIndex, scope, checkStd);
  }

  /** Runs every registered on-loaded callback (typically: revalidate all open documents). */
  private void fireOnLoaded() {
    for (Runnable cb : onLoadedCallbacks) {
      try {
        cb.run();
      } catch (Exception cbEx) {
        LOG.error("On-loaded callback failed: {}", cbEx.getMessage(), cbEx);
      }
    }
  }

  private static StrictnessLevel parseLevel(CimvocabcheckConfig config) {
    try {
      return StrictnessLevel.parse(config.strictness());
    } catch (IllegalArgumentException e) {
      LOG.warn(
          "Invalid strictness '{}' in config, using DEFAULT: {}",
          config.strictness(),
          e.getMessage());
      return StrictnessLevel.DEFAULT;
    }
  }

  private void notify(MessageType type, String message) {
    LanguageClient c = client.get();
    if (c != null) {
      c.showMessage(new MessageParams(type, message));
    }
  }
}
