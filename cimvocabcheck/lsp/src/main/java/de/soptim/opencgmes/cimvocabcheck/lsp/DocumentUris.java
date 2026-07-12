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

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Reduces a document URI to the file it denotes.
 *
 * <p>Ordinary documents arrive as {@code file:} URIs, notebook cells as {@code
 * vscode-notebook-cell:/path/to/notebook.cimnb.md#<cell-id>} — the notebook's path plus a cell-id
 * fragment. Both forms are mapped to the same plain filesystem path, which is what config
 * discovery, relative {@code # [endpoint=...]} resolution, and {@link NotebookDefaults} key on.
 */
final class DocumentUris {

  private static final String CELL_SCHEME = "vscode-notebook-cell:";

  private DocumentUris() {}

  /** Whether {@code uri} is a notebook cell rather than a document on disk. */
  static boolean isNotebookCell(String uri) {
    return uri != null && uri.toLowerCase(Locale.ROOT).startsWith(CELL_SCHEME);
  }

  /**
   * The file a document URI denotes — for a cell, the notebook file itself — or {@code null} when
   * the URI names no file (an unsaved {@code untitled:} document, an unparseable URI).
   */
  static Path path(String uri) {
    if (uri == null) {
      return null;
    }
    try {
      int hash = uri.indexOf('#');
      URI parsed = URI.create(hash >= 0 ? uri.substring(0, hash) : uri);
      if ("file".equalsIgnoreCase(parsed.getScheme())) {
        return Path.of(parsed);
      }
      String p = parsed.getPath();
      if (p == null || p.isBlank()) {
        return null;
      }
      // On Windows the cell URI path is "/C:/Users/…"; Path.of rejects the leading slash.
      if (p.length() >= 3 && p.charAt(0) == '/' && p.charAt(2) == ':') {
        p = p.substring(1);
      }
      return Path.of(p);
    } catch (Exception e) {
      return null;
    }
  }

  /** The directory containing a document URI's file, or {@code null} when it has none. */
  static Path dir(String uri) {
    Path path = path(uri);
    return path == null ? null : path.getParent();
  }
}
