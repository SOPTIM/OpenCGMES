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

package de.soptim.opencgmes.cimvocabcheck.lsp.notebook;

import de.soptim.opencgmes.cimxml.parser.CimXmlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphMapLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses local data files into query-ready {@link DatasetGraph}s for {@link LocalQueryExecutor},
 * caching the parses so re-running a cell does not re-read a large model.
 *
 * <p><b>File formats.</b> {@code *.xml} is parsed as a IEC 61970-552 CIMXML model via {@link
 * CimXmlParser} (header in a named graph, body in the default graph); everything else is handed to
 * Jena's {@link RDFDataMgr}, which picks the parser from the extension ({@code .ttl}, {@code .rdf},
 * {@code .owl}, {@code .nt}, {@code .nq}, {@code .trig}, …).
 *
 * <p><b>Caching.</b> Entries are keyed by absolute path and invalidated when the file's
 * modification time or size changes, checked on every access — editing a model and re-running the
 * cell always queries the current file. The cache is future-valued so concurrent cells hitting the
 * same not-yet-parsed file share one parse instead of racing, and LRU-bounded ({@value
 * #DEFAULT_CAPACITY} files by default) because CIMXML models can be large; evicted files are simply
 * re-parsed on next use.
 *
 * <p><b>Union semantics.</b> {@link #unionFor} exposes multiple files as one dataset: every named
 * graph of every file stays addressable via {@code GRAPH}, while the default graph is the union of
 * <em>all</em> graphs (default and named) of all files, so a bare {@code ?s ?p ?o} sees everything
 * — including a CIMXML model's header. Files with identical named-graph names (e.g. two CIMXML
 * FullModel headers, both {@code md:FullModel}) collide in {@code GRAPH} lookups — the last file
 * wins there — but remain fully visible in the default-graph union.
 *
 * <p>The returned datasets are read-only by convention: updates are rejected before execution (see
 * {@link ErrorCode#UPDATE_NOT_ALLOWED}), and nothing else writes to the cached graphs.
 */
final class LocalStoreManager {

  private static final Logger LOG = LoggerFactory.getLogger(LocalStoreManager.class);

  /** Default maximum number of parsed files kept in memory. */
  static final int DEFAULT_CAPACITY = 8;

  /** A cached parse: the file's attributes at parse time, used to detect staleness on access. */
  private record CachedEntry(FileTime mtime, long size, DatasetGraph graph) {}

  private final Map<Path, CompletableFuture<CachedEntry>> cache;

  LocalStoreManager() {
    this(DEFAULT_CAPACITY);
  }

  LocalStoreManager(int capacity) {
    this.cache = Collections.synchronizedMap(new LruCache(capacity));
  }

  /** Access-ordered {@link LinkedHashMap} bounded at {@code capacity} entries. */
  private static final class LruCache extends LinkedHashMap<Path, CompletableFuture<CachedEntry>> {

    private static final long serialVersionUID = 1L;

    private final int capacity;

    LruCache(int capacity) {
      super(16, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Path, CompletableFuture<CachedEntry>> eldest) {
      boolean evict = size() > capacity;
      if (evict) {
        LOG.debug(
            "Evicting parsed store {} (cache holds at most {} files)", eldest.getKey(), capacity);
      }
      return evict;
    }
  }

  /**
   * A file could not be read or parsed; {@link #code()} says which of the two it was, and the
   * message is already user-presentable (it names the file).
   */
  static final class StoreException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    StoreException(ErrorCode code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    ErrorCode code() {
      return code;
    }
  }

  /**
   * Returns one queryable dataset over {@code files} (absolute paths), parsing or re-using cached
   * parses per file — see the class comment for the union semantics.
   */
  DatasetGraph unionFor(List<Path> files) throws StoreException {
    List<DatasetGraph> parts = new ArrayList<>(files.size());
    for (Path file : files) {
      parts.add(datasetGraphFor(file));
    }

    MultiUnion defaultUnion = new MultiUnion();
    DatasetGraph union = new DatasetGraphMapLink(defaultUnion);
    for (DatasetGraph part : parts) {
      defaultUnion.addGraph(part.getDefaultGraph());
      part.listGraphNodes()
          .forEachRemaining(
              name -> {
                Graph graph = part.getGraph(name);
                defaultUnion.addGraph(graph);
                union.addGraph(name, graph);
              });
    }
    return union;
  }

  /**
   * Returns the (possibly cached) parse of one file, re-parsing when the file changed on disk.
   * Package-private so tests can observe cache behavior through instance identity.
   */
  DatasetGraph datasetGraphFor(Path file) throws StoreException {
    while (true) {
      BasicFileAttributes attrs = readAttributes(file);

      AtomicBoolean isLoader = new AtomicBoolean();
      CompletableFuture<CachedEntry> future =
          cache.computeIfAbsent(
              file,
              p -> {
                isLoader.set(true);
                return new CompletableFuture<>();
              });

      if (isLoader.get()) {
        // This thread inserted the future, so it owns the parse; concurrent callers of the same
        // file are waiting on the future below. A failed parse is not cached — the mapping is
        // removed so the next run retries (the file has usually been edited by then anyway).
        try {
          CachedEntry entry = new CachedEntry(attrs.lastModifiedTime(), attrs.size(), parse(file));
          future.complete(entry);
          return entry.graph();
        } catch (StoreException | RuntimeException | Error e) {
          cache.remove(file, future);
          future.completeExceptionally(e);
          throw e;
        }
      }

      CachedEntry entry = awaitEntry(file, future);
      if (entry.mtime().equals(attrs.lastModifiedTime()) && entry.size() == attrs.size()) {
        return entry.graph();
      }
      LOG.debug("Cached store for {} is stale, re-parsing", file);
      cache.remove(file, future);
    }
  }

  private static CachedEntry awaitEntry(Path file, CompletableFuture<CachedEntry> future)
      throws StoreException {
    try {
      return future.join();
    } catch (CompletionException | CancellationException e) {
      Throwable cause = e.getCause();
      if (cause instanceof StoreException storeException) {
        throw storeException;
      }
      throw new StoreException(
          ErrorCode.FILE_PARSE_ERROR, "Failed to parse " + file + ": " + e.getMessage(), e);
    }
  }

  private static BasicFileAttributes readAttributes(Path file) throws StoreException {
    try {
      BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
      if (!attrs.isRegularFile()) {
        throw new StoreException(ErrorCode.FILE_NOT_FOUND, "Not a file: " + file, null);
      }
      return attrs;
    } catch (IOException e) {
      throw new StoreException(ErrorCode.FILE_NOT_FOUND, "File not found: " + file, e);
    }
  }

  private static DatasetGraph parse(Path file) throws StoreException {
    long start = System.currentTimeMillis();
    try {
      Path fileName = file.getFileName();
      String name = fileName != null ? fileName.toString().toLowerCase(Locale.ROOT) : "";
      DatasetGraph parsed =
          name.endsWith(".xml")
              ? new CimXmlParser().parseCimModel(file)
              : RDFDataMgr.loadDatasetGraph(file.toUri().toString());
      LOG.debug("Parsed {} in {} ms", file, System.currentTimeMillis() - start);
      return parsed;
    } catch (IOException | RuntimeException e) {
      throw new StoreException(
          ErrorCode.FILE_PARSE_ERROR, "Failed to parse " + file + ": " + e.getMessage(), e);
    }
  }
}
