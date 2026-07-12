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

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.soptim.opencgmes.cimvocabcheck.core.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code "cimnotebook"} section from the nearest {@code opencgmes.jsonc} — the same
 * git-style nearest-config discovery and lenient JSONC parsing (comments, trailing commas) as the
 * validator's {@link ConfigLoader}, which owns the {@code "cimvocabcheck"} sibling section and is
 * untouched by this loader.
 *
 * <p>Reads are deliberately forgiving: a missing file, missing section, or unparseable file all
 * yield {@link NotebookConfig#EMPTY} (with a log line for the parse failure) — the validator
 * already surfaces config syntax problems to the user, notebooks don't need to pile on.
 *
 * <p><b>Caching.</b> Parsed configs are cached per file and invalidated when its modification time
 * or size changes, because this sits on the interactive path: a notebook cell without its own
 * {@code # [endpoint=...]} directive resolves its schema through here on every completion, hover,
 * and validation — i.e. on every keystroke. Only the parse is cached; the nearest-config discovery
 * still runs each time (a handful of {@code stat} calls), so a newly created {@code
 * opencgmes.jsonc} takes effect immediately.
 */
public final class NotebookConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(NotebookConfigLoader.class);

  private static final String SECTION = "cimnotebook";

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true)
          .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);

  /** A cached parse: the file's attributes at parse time, used to detect staleness on access. */
  private record CachedConfig(FileTime mtime, long size, NotebookConfig config) {}

  private static final Map<Path, CachedConfig> CACHE = new ConcurrentHashMap<>();

  private NotebookConfigLoader() {}

  /** A loaded config together with the file it came from ({@code null} when none was found). */
  public record Located(NotebookConfig config, Path configPath) {

    static final Located NONE = new Located(NotebookConfig.EMPTY, null);
  }

  /** Loads the config that applies to a notebook, via its directory. */
  static Located forNotebook(String notebookUri) {
    return forDirectory(NotebookPaths.notebookDir(notebookUri));
  }

  /** Loads the config that applies to documents in {@code dir} ({@code null} → none). */
  public static Located forDirectory(Path dir) {
    if (dir == null) {
      return Located.NONE;
    }
    Optional<Path> configFile = ConfigLoader.discoverFile(dir);
    return configFile.map(f -> new Located(load(f), f)).orElse(Located.NONE);
  }

  /**
   * The config file's {@code "cimnotebook"} section, from the cache when the file is unchanged
   * since it was last parsed. Forgiving as documented above.
   */
  static NotebookConfig load(Path configFile) {
    BasicFileAttributes attrs = readAttributes(configFile);
    if (attrs == null) {
      CACHE.remove(configFile);
      return NotebookConfig.EMPTY;
    }
    CachedConfig cached = CACHE.get(configFile);
    if (cached != null
        && cached.mtime().equals(attrs.lastModifiedTime())
        && cached.size() == attrs.size()) {
      return cached.config();
    }
    NotebookConfig config = parse(configFile);
    CACHE.put(configFile, new CachedConfig(attrs.lastModifiedTime(), attrs.size(), config));
    return config;
  }

  private static NotebookConfig parse(Path configFile) {
    try {
      JsonNode root = MAPPER.readTree(configFile.toFile());
      JsonNode section = root == null ? null : root.get(SECTION);
      if (section == null || section.isNull()) {
        return NotebookConfig.EMPTY;
      }
      return MAPPER.treeToValue(section, NotebookConfig.class);
    } catch (IOException | RuntimeException e) {
      LOG.warn("Ignoring unreadable {} section in {}: {}", SECTION, configFile, e.getMessage());
      return NotebookConfig.EMPTY;
    }
  }

  private static BasicFileAttributes readAttributes(Path configFile) {
    try {
      return Files.readAttributes(configFile, BasicFileAttributes.class);
    } catch (IOException e) {
      return null;
    }
  }
}
