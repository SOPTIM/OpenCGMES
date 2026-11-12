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

package de.soptim.opencgmes.cimxmlcli.output;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.sparql.core.DatasetGraph;

/**
 * Writes JSON-LD 1.1, either in expanded form (no {@code @context}, full IRIs; the default) or in
 * compacted form. The file is written to a temporary file next to the target and renamed at the
 * end, so that a failed run never leaves a half-written file behind.
 */
public final class JsonLdDatasetWriter {

  private static final int BUFFER_SIZE = 1 << 16;

  private final boolean compact;

  /** Creates a writer for expanded ({@code compact == false}) or compacted JSON-LD. */
  public JsonLdDatasetWriter(boolean compact) {
    this.compact = compact;
  }

  /**
   * Writes {@code dataset} to {@code target}. On failure the target is left untouched; a replaced
   * target keeps its POSIX permissions.
   *
   * @return the size of the written file in bytes
   */
  public long write(DatasetGraph dataset, OutputTarget target) throws IOException {
    final var path = target.path().toAbsolutePath();
    final var dir = Objects.requireNonNull(path.getParent(), "output must not be a root");
    Files.createDirectories(dir);
    // Not Files.createTempFile: that restricts the permissions to the owner
    final var tmp = dir.resolve("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
    try {
      try (var out = open(tmp, target.gzip())) {
        if (compact) {
          PrefixSanitizer.sanitize(dataset);
        }
        // Expanded form ignores the prefixes
        RDFDataMgr.write(
            out, dataset, compact ? RDFFormat.JSONLD11_PRETTY : RDFFormat.JSONLD11_PLAIN);
      }
      copyPermissions(path, tmp);
      move(tmp, path);
      return Files.size(path);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static OutputStream open(Path file, boolean gzip) throws IOException {
    final var out =
        new BufferedOutputStream(
            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), BUFFER_SIZE);
    if (!gzip) {
      return out;
    }
    try {
      return new GZIPOutputStream(out, BUFFER_SIZE);
    } catch (IOException e) {
      out.close();
      throw e;
    }
  }

  /** Keeps the POSIX permissions of a file that is replaced, e.g. an owner-only 0600 file. */
  private static void copyPermissions(Path from, Path to) throws IOException {
    final var source = Files.getFileAttributeView(from, PosixFileAttributeView.class);
    final var target = Files.getFileAttributeView(to, PosixFileAttributeView.class);
    if (source == null || target == null || !Files.exists(from)) {
      return;
    }
    target.setPermissions(source.readAttributes().permissions());
  }

  private static void move(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      // Some file systems cannot replace atomically
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
