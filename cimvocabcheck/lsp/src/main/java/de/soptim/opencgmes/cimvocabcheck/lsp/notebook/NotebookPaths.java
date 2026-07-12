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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the file paths of a {@link ExecuteTarget#TYPE_FILES} target against the notebook's
 * directory — the same base validation uses for relative {@code # [endpoint=...]} directives.
 * Shared by {@link LocalQueryExecutor} and {@link ShaclExecutor}.
 */
final class NotebookPaths {

  private static final Logger LOG = LoggerFactory.getLogger(NotebookPaths.class);

  private NotebookPaths() {}

  /** Resolves each directive path, relative ones against {@code notebookDir(notebookUri)}. */
  static List<Path> resolvePaths(List<String> files, String notebookUri)
      throws LocalStoreManager.StoreException {
    Path notebookDir = notebookDir(notebookUri);
    List<Path> paths = new ArrayList<>(files.size());
    for (String file : files) {
      paths.add(resolvePath(file, notebookDir));
    }
    return paths;
  }

  private static Path resolvePath(String file, Path notebookDir)
      throws LocalStoreManager.StoreException {
    Path path;
    try {
      path = Path.of(file);
    } catch (InvalidPathException e) {
      throw new LocalStoreManager.StoreException(
          ErrorCode.FILE_NOT_FOUND, "Not a valid file path: " + file, e);
    }
    if (path.isAbsolute()) {
      return path.normalize();
    }
    if (notebookDir == null) {
      throw new LocalStoreManager.StoreException(
          ErrorCode.FILE_NOT_FOUND,
          "Cannot resolve the relative path "
              + file
              + " — save the notebook first so relative paths have a base directory.",
          null);
    }
    return notebookDir.resolve(path).normalize();
  }

  /** The notebook's directory, or {@code null} when the notebook has no on-disk location. */
  private static Path notebookDir(String notebookUri) {
    if (notebookUri == null || notebookUri.isBlank()) {
      return null;
    }
    try {
      URI uri = new URI(notebookUri);
      if (!"file".equalsIgnoreCase(uri.getScheme())) {
        return null;
      }
      return Path.of(uri).getParent();
    } catch (URISyntaxException | IllegalArgumentException e) {
      LOG.debug("Ignoring unusable notebook URI {}: {}", notebookUri, e.getMessage());
      return null;
    }
  }
}
