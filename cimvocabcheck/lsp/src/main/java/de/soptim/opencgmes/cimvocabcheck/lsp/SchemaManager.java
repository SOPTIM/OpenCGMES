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
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookConfigLoader;
import de.soptim.opencgmes.cimvocabcheck.lsp.notebook.NotebookConnection;
import de.soptim.opencgmes.cimvocabcheck.lsp.schema.SchemaLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicReference;
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
    return resolveFrom(schemaSourceOf(endpoint, docDir), docDir);
  }

  /**
   * The source an endpoint directive loads its schema from — a remote SPARQL endpoint URL or a
   * local {@code .ttl}/{@code .rdf}/{@code .owl} file — or {@code null} when the directive names no
   * schema at all and the document's workspace schema applies instead. That is the case for a blank
   * directive, and for two notebook-specific ones:
   *
   * <ul>
   *   <li><b>Instance data.</b> The same directive is also the cell's execution target, which may
   *       be a CIMXML model or an {@code .nt}/{@code .nq}/{@code .trig} dump. Loading data as a
   *       schema would turn every term in the cell into a false diagnostic.
   *   <li><b>An unresolvable connection name.</b> Names with no matching {@code "cimnotebook"}
   *       connection, or whose connection declares no remote URL, have no schema to offer.
   * </ul>
   */
  String schemaSourceOf(String endpoint, Path docDir) {
    if (endpoint == null || endpoint.isBlank()) {
      return null;
    }
    if (isRemote(endpoint)) {
      return endpoint;
    }
    if (looksLikeConnectionName(endpoint)) {
      // Notebook cells may name a connection from the "cimnotebook" config section instead of a
      // URL or file. Resolving it here keeps validation and execution agreeing on what the
      // directive means: the schema loads from the connection's endpoint.
      NotebookConnection connection =
          NotebookConfigLoader.forDirectory(docDir).config().byName(endpoint);
      return connection != null && connection.url() != null && isRemote(connection.url())
          ? connection.url()
          : null;
    }
    Path file = resolveLocalEndpoint(endpoint, docDir);
    if (!isSchemaFile(file)) {
      LOG.debug("Endpoint directive {} is not a schema file; using the workspace schema", file);
      return null;
    }
    return file.toString();
  }

  /**
   * Loads the schema from a {@link #schemaSourceOf} result, caching it by source; {@code null} —
   * i.e. no schema source — falls back to the document's workspace schema (the nearest {@code
   * opencgmes.jsonc} above it). Empty when nothing applies: no config, a config without schemas, or
   * an endpoint that is still loading or failed — the caller then validates syntax-only.
   */
  Optional<ResolvedSchema> resolveFrom(String source, Path docDir) {
    if (source == null) {
      return workspaceSchemaFor(docDir).map(WorkspaceSchema::toResolvedSchema);
    }
    return isRemote(source) ? resolveRemote(source) : resolveLocal(Path.of(source));
  }

  /**
   * Whether a directive could be a named connection: no path separators, no extension dot. Only
   * such directives pay for a config lookup; files virtually always have an extension, so
   * "model.xml" never does, while "local-fuseki" does. (A connection whose name contains a dot or
   * slash is not resolvable from a directive — documented limitation.)
   */
  private static boolean looksLikeConnectionName(String endpoint) {
    return !endpoint.contains("/") && !endpoint.contains("\\") && !endpoint.contains(".");
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
      return primaryConfigKey == null ? Optional.empty() : primarySchema();
    }
    Optional<Path> configFile = ConfigLoader.discoverFile(docDir);
    if (configFile.isEmpty()) { // no config → syntax-only
      return Optional.empty();
    }
    String key = configFile.get().toString();
    if (key.equals(primaryConfigKey)) {
      return primarySchema();
    }
    WorkspaceSchema ws = workspaceSchemaCache.computeIfAbsent(key, this::buildForKey);
    return ws.api() == null ? Optional.empty() : Optional.of(ws);
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
      return buildSchemaForConfig(Path.of(key));
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

  /** Loads a schema from a local file synchronously (fast); caches success and failure. */
  private Optional<ResolvedSchema> resolveLocal(Path file) {
    String key = file.toString();
    ResolvedSchema cached = endpointCache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    if (isFailed(key)) {
      return Optional.empty();
    }
    try {
      if (!Files.isRegularFile(file)) {
        fail(key, MessageType.Warning, "CIMVocabCheck: endpoint schema file not found: " + file);
        return Optional.empty();
      }
      // loadIndexWithSources (rather than loadIndex) keeps the VersionIri → Path mapping so a
      // DefinitionIndex can be built below — go-to-definition on a local-file endpoint must jump
      // into that real file, not fall back to the remote-only EndpointDefinitionPeek.
      var loaded = CgmesSchemaLoader.fromFiles(List.of(file)).loadIndexWithSources();
      var defIndex = DefinitionIndex.build(loaded.index(), loaded.sourcePaths());
      ResolvedSchema schema = buildSchema(loaded.index(), Map.of(), defIndex);
      endpointCache.put(key, schema);
      LOG.info("Loaded endpoint schema from file {}", file);
      return Optional.of(schema);
    } catch (Exception e) {
      fail(
          key,
          MessageType.Error,
          "CIMVocabCheck: failed to load schema from " + file + " — " + e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Resolves a schema hosted on a remote SPARQL endpoint. The fetch (one enumeration query plus a
   * CONSTRUCT per profile graph) runs on the dedicated {@link #endpointExecutor} so neither the
   * validator thread nor workspace reloads block on it; open documents are revalidated once the
   * schema lands. Returns empty until then.
   */
  private Optional<ResolvedSchema> resolveRemote(String endpoint) {
    ResolvedSchema cached = endpointCache.get(endpoint);
    if (cached != null) {
      return Optional.of(cached);
    }
    if (isFailed(endpoint)) {
      return Optional.empty();
    }
    if (inFlightEndpoints.add(endpoint)) {
      notify(MessageType.Info, "CIMVocabCheck: loading schema from endpoint " + endpoint + " …");
      endpointExecutor.submit(() -> loadRemoteEndpoint(endpoint));
    }
    return Optional.empty();
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
    // Endpoint schemas and per-config schemas are cached for the session; drop them on reload so
    // a strictness change propagates and any transient load failures get retried.
    endpointCache.clear();
    failedEndpoints.clear();
    workspaceSchemaCache.clear();

    Optional<Path> configFile = ConfigLoader.discoverFile(root);
    primaryConfigKey = configFile.map(Path::toString).orElse(null);
    try {
      WorkspaceSchema primary =
          configFile.isPresent() ? buildSchemaForConfig(configFile.get()) : noSchemaWorkspace();
      apiRef.set(primary.api());
      levelRef.set(primary.level());
      defRef.set(primary.definitionIndex());
      namedGraphRef.set(primary.namedGraphScope());
      checkStdVocabRef.set(primary.checkStandardVocab());

      if (primary.api() == null) {
        LOG.info(
            "No schema configured under {} — syntax-only unless a # [endpoint=...] is used", root);
        notify(
            MessageType.Info,
            "CIMVocabCheck: no schema configured — checking SPARQL/SHACL syntax "
                + "only. Add \"schemas\" to opencgmes.jsonc, or a \"# [endpoint=...]\" directive, "
                + "for schema-based validation.");
      } else {
        LOG.info(
            "Schema loaded successfully from {} (strictness: {})",
            configFile.get(),
            primary.level());
        notify(MessageType.Info, "CIMVocabCheck: schema loaded successfully.");
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
  private WorkspaceSchema buildSchemaForConfig(Path configFile) throws Exception {
    Path base = configFile.toAbsolutePath().getParent();
    CimvocabcheckConfig config = ConfigLoader.load(configFile);
    Optional<SchemaLoader.SchemaAndSources> loaded = SchemaLoader.loadWithSources(config, base);
    if (loaded.isEmpty()) {
      // Config present but no schemas declared → syntax-only (unless documents use an endpoint).
      return new WorkspaceSchema(
          null, parseLevel(config), null, Map.of(), config.checkStandardVocabulary());
    }
    return assemble(config, loaded.get());
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
