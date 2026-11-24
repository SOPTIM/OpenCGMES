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

package de.soptim.opencgmes.cimxmlcli.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Provides all files with a given extension below a folder, searched recursively. ZIP archives are
 * opened and searched as well, including ZIP archives nested inside ZIP archives.
 */
public final class FileTreeSourceProvider {

  /** Maximum nesting depth of ZIP archives; protects against ZIP quines and zip bombs. */
  public static final int DEFAULT_MAX_ZIP_DEPTH = 8;

  private final String extension;
  private final int maxZipDepth;

  /**
   * Creates a provider for files ending with {@code extension} (e.g. {@code ".xml"}), compared
   * case-insensitively.
   */
  public FileTreeSourceProvider(String extension) {
    this(extension, DEFAULT_MAX_ZIP_DEPTH);
  }

  /** Creates a provider with a custom maximum ZIP nesting depth. */
  public FileTreeSourceProvider(String extension, int maxZipDepth) {
    if (maxZipDepth < 1) {
      throw new IllegalArgumentException("maxZipDepth must be at least 1: " + maxZipDepth);
    }
    this.extension = extension.toLowerCase(Locale.ROOT);
    this.maxZipDepth = maxZipDepth;
  }

  /** Returns the extension of the files this provider returns, e.g. {@code ".xml"}. */
  public String extension() {
    return extension;
  }

  /** Starts iterating over the sources below {@code root}. */
  public FileTreeSourceIterator open(Path root) throws IOException {
    return new FileTreeSourceIterator(root, extension, maxZipDepth);
  }
}
