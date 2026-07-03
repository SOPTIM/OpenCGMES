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

package de.soptim.opencgmes.cimvocabcheck.cli.schema;

import de.soptim.opencgmes.cimvocabcheck.core.CgmesSchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.config.CimvocabcheckConfig;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a {@link RdfsSchemaIndex} from the CLI config or from explicit schema file paths. */
public final class SchemaLoader {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaLoader.class);

  private SchemaLoader() {}

  /**
   * Loads an index from the given config (either {@code schemasDirectory} or explicit {@code
   * schemas} list). Paths in the config are resolved relative to {@code configBase}. Returns {@link
   * Optional#empty()} when the config declares no schemas — there is no bundled default, so the
   * caller validates syntax-only.
   *
   * @throws SchemaLoadException if schema files are configured but none can be found/parsed
   */
  public static Optional<RdfsSchemaIndex> load(CimvocabcheckConfig config, Path configBase)
      throws SchemaLoadException {
    if (config.schemas().isEmpty() && config.schemasDirectory() == null) {
      return Optional.empty();
    }
    return Optional.of(buildIndex(resolveFiles(config, configBase)));
  }

  /**
   * Loads an index from an explicit list of schema file paths. Any path that is a directory is
   * expanded to the {@code .rdf}/{@code .ttl}/{@code .owl} files it directly contains, so {@code
   * --schema <dir>} works for a mounted schema folder.
   *
   * @throws SchemaLoadException if the list is empty, resolves to no schema files, or a file fails
   *     to parse
   */
  public static RdfsSchemaIndex load(List<Path> schemaFiles) throws SchemaLoadException {
    if (schemaFiles.isEmpty()) {
      throw new SchemaLoadException("No schema files provided.");
    }
    List<Path> files = expandDirectories(schemaFiles);
    if (files.isEmpty()) {
      throw new SchemaLoadException(
          "No .rdf / .ttl / .owl files found in the given schema path(s): " + schemaFiles);
    }
    return buildIndex(files);
  }

  // ---- private helpers -------------------------------------------------------------------

  /**
   * Expands any directory in {@code paths} into the schema files it directly contains (depth 1),
   * keeping plain file paths as-is. This lets {@code --schema <dir>} point at a mounted schema
   * folder, mirroring how the config's {@code schemasDirectory} is resolved.
   */
  private static List<Path> expandDirectories(List<Path> paths) throws SchemaLoadException {
    var files = new ArrayList<Path>();
    for (Path p : paths) {
      if (Files.isDirectory(p)) {
        try (Stream<Path> walk = Files.walk(p, 1, FileVisitOption.FOLLOW_LINKS)) {
          walk.filter(SchemaLoader::isSchemaFile).sorted().forEach(files::add);
        } catch (IOException e) {
          throw new SchemaLoadException(
              "Cannot list schema directory " + p + ": " + e.getMessage(), e);
        }
      } else {
        files.add(p);
      }
    }
    return files;
  }

  private static List<Path> resolveFiles(CimvocabcheckConfig config, Path base)
      throws SchemaLoadException {
    if (!config.schemas().isEmpty()) {
      return config.schemas().stream()
          .map(s -> base.resolve(s).normalize())
          .collect(Collectors.toList());
    }
    if (config.schemasDirectory() != null) {
      Path dir = base.resolve(config.schemasDirectory()).normalize();
      if (!Files.isDirectory(dir)) {
        throw new SchemaLoadException(
            "schemasDirectory does not exist or is not a directory: " + dir);
      }
      try (Stream<Path> walk = Files.walk(dir, 1, FileVisitOption.FOLLOW_LINKS)) {
        var files = walk.filter(SchemaLoader::isSchemaFile).sorted().collect(Collectors.toList());
        if (files.isEmpty()) {
          throw new SchemaLoadException(
              "No .rdf / .ttl / .owl files found in schemasDirectory: " + dir);
        }
        return files;
      } catch (IOException e) {
        throw new SchemaLoadException(
            "Cannot list schemasDirectory " + dir + ": " + e.getMessage(), e);
      }
    }
    throw new SchemaLoadException("Config must specify either 'schemasDirectory' or 'schemas'.");
  }

  private static boolean isSchemaFile(Path path) {
    Path name = path.getFileName();
    return name != null && isSchemaFile(name.toString());
  }

  private static boolean isSchemaFile(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".rdf") || lower.endsWith(".ttl") || lower.endsWith(".owl");
  }

  private static RdfsSchemaIndex buildIndex(List<Path> files) throws SchemaLoadException {
    try {
      var loaded = CgmesSchemaLoader.fromFiles(files).loadIndexWithSources();
      loaded.skippedFiles().forEach(f -> LOG.warn("Skipped unparseable schema file: {}", f));
      return loaded.index();
    } catch (CgmesSchemaLoader.SchemaLoadException e) {
      throw new SchemaLoadException(e.getMessage(), e.getCause());
    }
  }

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
