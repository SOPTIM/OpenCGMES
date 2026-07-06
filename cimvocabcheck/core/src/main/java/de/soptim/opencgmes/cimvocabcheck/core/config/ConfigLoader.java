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

package de.soptim.opencgmes.cimvocabcheck.core.config;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.soptim.opencgmes.cimxml.graph.CimNamespaceFactoryRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Locates and parses the project config file {@code opencgmes.jsonc}, shared by the CLI and LSP
 * modules so the discovery rules and parsing behaviour cannot drift apart.
 *
 * <p>All CIMVocabCheck settings live under a top-level {@code "cimvocabcheck"} object so {@code
 * opencgmes.jsonc} can host configuration for other OpenCGMES tools alongside it:
 *
 * <pre>{@code
 * {
 *   "cimvocabcheck": {
 *     "strictness": "strict",
 *     "namedGraphs": { "urn:uuid:eq": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"] }
 *   }
 * }
 * }</pre>
 *
 * <p>Auto-discovery walks the directory tree upward from a start directory looking for {@code
 * opencgmes.jsonc}; an explicit path can also be provided. The file is optional — when none is
 * found (or it declares no {@code schemas}/{@code schemasDirectory}), validation is syntax-only;
 * there is no bundled default schema. Java-style comments and trailing commas are tolerated.
 */
public final class ConfigLoader {

  /** The config file name, looked for in each directory while walking up the tree. */
  public static final String CONFIG_FILENAME = "opencgmes.jsonc";

  /** Top-level key under which all CIMVocabCheck settings live. */
  private static final String SECTION = "cimvocabcheck";

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true)
          .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);

  private ConfigLoader() {}

  /**
   * Loads the {@code cimvocabcheck} section from an explicit {@code opencgmes.jsonc} path. A
   * missing section yields an empty config (no schemas → syntax-only validation).
   *
   * <p>As a side effect, any {@code cimNamespaces} entries are registered with {@link
   * CimNamespaceFactoryRegistry} so subsequent schema loads (from files or a SPARQL endpoint) can
   * resolve those namespaces. This registry is process-global — in a multi-root workspace, the last
   * config loaded for a given namespace wins.
   *
   * @throws ConfigException if the file cannot be read or parsed, or a {@code cimNamespaces} entry
   *     names an unknown profile shape
   */
  public static CimvocabcheckConfig load(Path configFile) throws ConfigException {
    try {
      JsonNode root = MAPPER.readTree(configFile.toFile());
      JsonNode section = root == null ? null : root.get(SECTION);
      CimvocabcheckConfig config =
          (section == null || section.isNull())
              ? CimvocabcheckConfig.empty()
              : MAPPER.treeToValue(section, CimvocabcheckConfig.class);
      registerCimNamespaces(config, configFile);
      return config;
    } catch (IOException e) {
      throw new ConfigException("Cannot read config file " + configFile + ": " + e.getMessage(), e);
    }
  }

  /**
   * Registers the config's {@code cimNamespaces} (namespace URI → profile shape) with {@link
   * CimNamespaceFactoryRegistry}.
   */
  private static void registerCimNamespaces(CimvocabcheckConfig config, Path configFile)
      throws ConfigException {
    for (Map.Entry<String, String> entry : config.cimNamespaces().entrySet()) {
      String namespace = entry.getKey();
      String shape = entry.getValue();
      try {
        CimNamespaceFactoryRegistry.registerProfileFactory(
            namespace, CimProfileShapes.resolve(shape));
      } catch (IllegalArgumentException e) {
        throw new ConfigException(
            "Invalid 'cimNamespaces' entry in "
                + configFile
                + " for namespace '"
                + namespace
                + "': "
                + e.getMessage());
      }
    }
  }

  /**
   * Walks upward from {@code startDir} looking for {@code opencgmes.jsonc}.
   *
   * @return the parsed {@code cimvocabcheck} section, or empty if no file was found in the
   *     hierarchy
   * @throws ConfigException if a config file is found but cannot be parsed
   */
  public static Optional<CimvocabcheckConfig> discover(Path startDir) throws ConfigException {
    Optional<Path> file = discoverFile(startDir);
    return file.isPresent() ? Optional.of(load(file.get())) : Optional.empty();
  }

  /**
   * Walks upward from {@code startDir} returning the path of the nearest {@code opencgmes.jsonc},
   * or empty if none exists anywhere in the hierarchy. The file is not parsed.
   */
  public static Optional<Path> discoverFile(Path startDir) {
    if (startDir == null) {
      return Optional.empty();
    }
    Path dir = startDir.toAbsolutePath().normalize();
    while (dir != null) {
      Path candidate = dir.resolve(CONFIG_FILENAME);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate);
      }
      dir = dir.getParent();
    }
    return Optional.empty();
  }

  /** Thrown when the config file cannot be loaded or parsed. */
  public static final class ConfigException extends Exception {
    /** Creates an exception with a message and an underlying cause. */
    public ConfigException(String message, Throwable cause) {
      super(message, cause);
    }

    /** Creates an exception with a message only. */
    public ConfigException(String message) {
      super(message);
    }
  }
}
