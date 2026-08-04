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

  /** Validates that the source names something to read. */
  public RdfArchitectSource {
    Objects.requireNonNull(baseUrl, "baseUrl");
    if (dataset == null && snapshot == null) {
      throw new IllegalArgumentException("an RDFArchitect source needs a dataset or a snapshot");
    }
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
    Objects.requireNonNull(url, "url");
    URI uri;
    try {
      uri = new URI(url.trim());
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

  /** The instance root: the URL without its deep-link path ({@code /mainpage}) and query. */
  private static String baseOf(URI uri) {
    String path = Optional.ofNullable(uri.getPath()).orElse("");
    if (path.endsWith("/mainpage")) {
      path = path.substring(0, path.length() - "/mainpage".length());
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String authority = uri.getRawAuthority();
    return uri.getScheme() + "://" + authority + path;
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
