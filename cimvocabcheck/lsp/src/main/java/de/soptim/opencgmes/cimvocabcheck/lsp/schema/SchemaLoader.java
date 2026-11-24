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

package de.soptim.opencgmes.cimvocabcheck.lsp.schema;

import de.soptim.opencgmes.cimvocabcheck.core.CgmesSchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.CgmesSchemaLoader.LoadedIndex;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.config.CimvocabcheckConfig;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds a {@link RdfsSchemaIndex} from an {@link CimvocabcheckConfig}, delegating to {@link
 * CgmesSchemaLoader} for file discovery and parsing.
 */
public final class SchemaLoader {

  private SchemaLoader() {}

  /**
   * Carries the loaded index together with the {@link VersionIri} → source-file mapping needed for
   * go-to-definition and workspace symbol navigation.
   *
   * <p>{@link #skippedFiles()} is non-empty when one or more schema files could not be parsed;
   * callers should surface these as user-visible warnings.
   */
  public record SchemaAndSources(
      RdfsSchemaIndex index, Map<VersionIri, Path> sourcePaths, List<String> skippedFiles) {}

  /**
   * Loads the index and source-file map from the given LSP config, or {@link Optional#empty()} when
   * the config declares no {@code schemas}/{@code schemasDirectory} — in which case the caller
   * validates syntax only (no bundled default schema). Paths resolve relative to {@code
   * configBase}.
   *
   * <p>Files that cannot be parsed are recorded in {@link SchemaAndSources#skippedFiles()} rather
   * than causing a hard failure; the load only fails if no valid CIM profile loads.
   *
   * @throws SchemaLoadException if schema files are configured but none could be parsed/registered
   */
  public static Optional<SchemaAndSources> loadWithSources(
      CimvocabcheckConfig config, Path configBase) throws SchemaLoadException {
    Optional<CgmesSchemaLoader> loader = resolveLoader(config, configBase);
    if (loader.isEmpty()) {
      return Optional.empty();
    }
    try {
      LoadedIndex loaded = loader.get().loadIndexWithSources();
      return Optional.of(
          new SchemaAndSources(loaded.index(), loaded.sourcePaths(), loaded.skippedFiles()));
    } catch (CgmesSchemaLoader.SchemaLoadException e) {
      throw new SchemaLoadException(e.getMessage(), e);
    }
  }

  /**
   * Resolves the schema files a config declares — the explicit {@code schemas} list or the contents
   * of {@code schemasDirectory} — without parsing them. Returns an empty list when the config
   * declares no schemas. Paths resolve relative to {@code configBase}.
   *
   * @throws SchemaLoadException if the schemas directory does not exist or contains no schema files
   */
  public static List<Path> resolveSchemaFiles(CimvocabcheckConfig config, Path configBase)
      throws SchemaLoadException {
    Optional<CgmesSchemaLoader> loader = resolveLoader(config, configBase);
    if (loader.isEmpty()) {
      return List.of();
    }
    try {
      return loader.get().resolveSchemaFiles();
    } catch (CgmesSchemaLoader.SchemaLoadException e) {
      throw new SchemaLoadException(e.getMessage(), e);
    }
  }

  // ---- Private ---------------------------------------------------------------------------

  private static Optional<CgmesSchemaLoader> resolveLoader(CimvocabcheckConfig config, Path base) {
    if (!config.schemas().isEmpty()) {
      List<Path> files =
          config.schemas().stream()
              .map(s -> base.resolve(s).normalize())
              .collect(Collectors.toList());
      return Optional.of(CgmesSchemaLoader.fromFiles(files));
    }
    if (config.schemasDirectory() != null) {
      Path dir = base.resolve(config.schemasDirectory()).normalize();
      return Optional.of(CgmesSchemaLoader.fromDirectory(dir));
    }
    return Optional.empty(); // no schemas configured — syntax-only (no bundled default)
  }

  // ---- Exception -------------------------------------------------------------------------

  /** Thrown when schema loading fails. */
  public static final class SchemaLoadException extends Exception {
    /** Creates an exception with a message only. */
    public SchemaLoadException(String message) {
      super(message);
    }

    /** Creates an exception with a message and an underlying cause. */
    public SchemaLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
