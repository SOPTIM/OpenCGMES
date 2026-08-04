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

package de.soptim.opencgmes.cimvocabcheck.core.schema;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Which schema to read from which RDFArchitect instance.
 *
 * <p>A source is written as an ordinary RDFArchitect link, so it can be copied out of the running
 * application rather than assembled by hand:
 *
 * <pre>{@code
 * http://localhost:3000/?snapshot=ffPKWuq2hw8WKBRn5VwEOA   // a shared snapshot ("Share" dialog)
 * http://localhost:3000/mainpage?dataset=cgmes-3.0         // a dataset of the instance
 * }</pre>
 *
 * <p>Prefer the snapshot form. RDFArchitect scopes datasets to the browser session, so a plain
 * {@code dataset} is only visible to other clients when the instance is backed by a triple store; a
 * snapshot is addressable from any session and is immutable, which is what a validation schema
 * should be.
 *
 * @param baseUrl the instance root, without the deep-link path or query
 * @param dataset the dataset to read, or {@code null} to derive it from {@code snapshot}
 * @param snapshot the snapshot token to load first, or {@code null} to read {@code dataset} live
 */
public record RdfArchitectSource(String baseUrl, String dataset, String snapshot) {

  /** How RDFArchitect names the dataset a loaded snapshot appears as. */
  private static final String SNAPSHOT_PREFIX = "SNAPSHOT_";

  /** Validates that the source names something to read, and recognises a snapshot by its name. */
  public RdfArchitectSource {
    Objects.requireNonNull(baseUrl, "baseUrl");
    if (dataset == null && snapshot == null) {
      throw new IllegalArgumentException("an RDFArchitect source needs a dataset or a snapshot");
    }
    if (snapshot == null) {
      snapshot = snapshotTokenOf(dataset);
    }
  }

  /**
   * The snapshot token embedded in a dataset name, or {@code null} when the name is not a loaded
   * snapshot's.
   *
   * <p>RDFArchitect names a loaded snapshot {@code SNAPSHOT_<dataset>_<token>}, and that name is
   * what a user sees in the address bar — so it is what they copy into a config. The name alone is
   * useless to anyone else, because the dataset exists only in sessions that loaded the snapshot;
   * recovering the token means the snapshot can simply be loaded instead of reported missing.
   */
  private static String snapshotTokenOf(String dataset) {
    if (dataset == null || !dataset.startsWith(SNAPSHOT_PREFIX)) {
      return null;
    }
    int tokenStart = dataset.lastIndexOf('_') + 1;
    // "SNAPSHOT_<dataset>_<token>" — a name with nothing after the prefix has no token to take.
    return tokenStart > SNAPSHOT_PREFIX.length() && tokenStart < dataset.length()
        ? dataset.substring(tokenStart)
        : null;
  }

  /**
   * Parses an RDFArchitect link.
   *
   * <p>Anything the application itself produces is accepted: the snapshot share link, a {@code
   * /mainpage} deep link, or the bare instance URL with a {@code dataset} or {@code snapshot} query
   * parameter.
   *
   * @throws IllegalArgumentException if the URL is malformed or names neither a dataset nor a
   *     snapshot
   */
  public static RdfArchitectSource parse(String url) {
    return parse(url, null);
  }

  /**
   * Parses an RDFArchitect link, or a bare dataset name against a connected instance.
   *
   * <p>A value that is not a URL — {@code "cgmes-3.0"} — names a dataset of the instance an editor
   * is connected to. That is how a workspace refers to the dataset being edited without pinning an
   * instance URL into a config file, and it is the form that reads a *live* dataset.
   *
   * @param connectedBaseUrl the instance an editor is connected to, or {@code null} when none is
   * @throws IllegalArgumentException if the value is a bare dataset name while nothing is
   *     connected, or is a URL naming neither a dataset nor a snapshot
   */
  public static RdfArchitectSource parse(String url, String connectedBaseUrl) {
    Objects.requireNonNull(url, "url");
    String value = url.trim();
    if (!looksLikeUrl(value)) {
      if (connectedBaseUrl == null || connectedBaseUrl.isBlank()) {
        throw new IllegalArgumentException(
            "\""
                + value
                + "\" names a dataset, but no RDFArchitect session is connected. That needs the"
                + " RDFArchitect view open in the editor *and* an instance that reports its session"
                + " to it (see the CIMNotebook docs on live datasets). Otherwise name the instance"
                + " in full, e.g. http://localhost:3000/?dataset="
                + value
                + " — or, for a schema that should not change underneath you, a snapshot link.");
      }
      return new RdfArchitectSource(stripTrailingSlashes(connectedBaseUrl.trim()), value, null);
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("not a valid RDFArchitect URL: " + url, e);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "RDFArchitect URL must be absolute, e.g. http://localhost:3000/?snapshot=<token>: "
              + url);
    }
    String snapshot = queryParam(uri.getRawQuery(), "snapshot");
    String dataset = queryParam(uri.getRawQuery(), "dataset");
    if (snapshot == null && dataset == null) {
      throw new IllegalArgumentException(
          "RDFArchitect URL names neither a dataset nor a snapshot — copy a snapshot link from"
              + " RDFArchitect's Share dialog, or append ?dataset=<name>: "
              + url);
    }
    return new RdfArchitectSource(baseOf(uri), dataset, snapshot);
  }

  /** Whether a config value addresses an instance rather than naming a dataset of one. */
  private static boolean looksLikeUrl(String value) {
    return value.contains("://");
  }

  /** The instance root: the URL without its deep-link path ({@code /mainpage}) and query. */
  private static String baseOf(URI uri) {
    String path = Optional.ofNullable(uri.getPath()).orElse("");
    if (path.endsWith("/mainpage")) {
      path = path.substring(0, path.length() - "/mainpage".length());
    }
    return uri.getScheme() + "://" + uri.getRawAuthority() + stripTrailingSlashes(path);
  }

  private static String stripTrailingSlashes(String value) {
    String stripped = value;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  private static String queryParam(String rawQuery, String name) {
    if (rawQuery == null) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
        String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  /** A short description for log and diagnostic messages. */
  public String describe() {
    return snapshot != null
        ? baseUrl + " (snapshot " + snapshot + ")"
        : baseUrl + " (" + dataset + ")";
  }
}
