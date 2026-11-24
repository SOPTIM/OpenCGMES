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

import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Iterates over the sources below a folder: walks it recursively and descends into ZIP archives
 * iteratively, using an explicit stack of open archives instead of recursion. Files are visited in
 * sorted order so that runs are reproducible. Archives that cannot be read are skipped and reported
 * by {@link #problems()} instead of aborting the iteration.
 */
public final class FileTreeSourceIterator implements Iterator<CimSource>, Closeable {

  private static final String ZIP_EXTENSION = ".zip";
  private static final String ZIP_SEPARATOR = "!/";

  private final Path root;
  private final String extension;
  private final int maxZipDepth;
  private final Iterator<Path> files;
  private final Deque<OpenZip> zips = new ArrayDeque<>();
  private final List<Problem> problems = new ArrayList<>();
  private CimSource next;

  FileTreeSourceIterator(Path root, String extension, int maxZipDepth) throws IOException {
    this.root = root;
    this.extension = extension;
    this.maxZipDepth = maxZipDepth;
    // Sorted up front so every run sees the same order
    try (var walk = Files.walk(root)) {
      this.files =
          walk.filter(Files::isRegularFile)
              .filter(p -> isZip(fileName(p)) || accepts(fileName(p)))
              .sorted()
              .toList()
              .iterator();
    } catch (UncheckedIOException e) {
      // Files.walk wraps I/O errors in unchecked exceptions
      throw e.getCause();
    }
  }

  @Override
  public boolean hasNext() {
    if (next == null) {
      next = advance();
    }
    return next != null;
  }

  @Override
  public CimSource next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    final var result = next;
    next = null;
    return result;
  }

  /** Returns the problems found so far, e.g. unreadable or too deeply nested ZIP archives. */
  public List<Problem> problems() {
    return List.copyOf(problems);
  }

  @Override
  public void close() throws IOException {
    // Innermost first; only the outermost archive closes the file
    while (!zips.isEmpty()) {
      zips.pop().stream().close();
    }
  }

  private CimSource advance() {
    while (true) {
      // Finish the open archives before the next file
      if (!zips.isEmpty()) {
        final var entry = nextZipEntry(zips.peek());
        if (entry != null) {
          return entry;
        }
        continue;
      }
      if (!files.hasNext()) {
        return null;
      }
      final var file = files.next();
      final var path = displayPath(file);
      if (!isZip(fileName(file))) {
        return new FileSource(file, path);
      }
      // ZIP file: its entries follow in the next iterations
      try {
        zips.push(
            new OpenZip(
                new ZipInputStream(new BufferedInputStream(Files.newInputStream(file))), path));
      } catch (IOException e) {
        problems.add(Problem.of(path, "cannot open ZIP archive: " + e.getMessage()));
      }
    }
  }

  /**
   * Returns the next matching entry of {@code zip}, or {@code null} if the caller has to look at
   * the top of the stack again (because an archive was finished or a nested archive was opened).
   */
  private CimSource nextZipEntry(OpenZip zip) {
    final ZipEntry entry;
    try {
      entry = zip.stream().getNextEntry();
    } catch (IOException | IllegalArgumentException e) {
      problems.add(Problem.of(zip.path(), "cannot read ZIP archive: " + e.getMessage()));
      closeTop();
      return null;
    }
    if (entry == null) {
      // ZipInputStream also ends silently on bytes that are no ZIP archive
      if (!zip.hasEntries) {
        problems.add(
            new Problem(
                zip.path(),
                "is not a valid ZIP archive or contains no entries",
                "Check the archive or remove it from the folder."));
      }
      // Archive finished: back to the enclosing archive or next file
      closeTop();
      return null;
    }
    zip.hasEntries = true;
    if (entry.isDirectory()) {
      return null;
    }
    final var path = zip.path() + ZIP_SEPARATOR + entry.getName();
    if (isZip(entry.getName())) {
      if (zips.size() >= maxZipDepth) {
        problems.add(
            new Problem(
                path,
                "ZIP archive nested deeper than " + maxZipDepth + " levels; skipped",
                "Extract the outer archives first."));
      } else {
        // Read straight from the outer stream, without a temp file
        zips.push(new OpenZip(new ZipInputStream(new NonClosingInputStream(zip.stream())), path));
      }
      return null;
    }
    return accepts(entry.getName()) ? new ZipEntrySource(zip.stream(), path) : null;
  }

  private void closeTop() {
    try {
      zips.pop().stream().close();
    } catch (IOException ignored) {
      // Nothing left to read; a failing close loses no data
    }
  }

  private boolean accepts(String fileName) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(extension);
  }

  private static boolean isZip(String fileName) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(ZIP_EXTENSION);
  }

  private static String fileName(Path file) {
    final var name = file.getFileName();
    return name == null ? "" : name.toString();
  }

  private String displayPath(Path file) {
    final var relative = root.relativize(file);
    return relative.toString().replace(file.getFileSystem().getSeparator(), "/");
  }

  /** An archive that is currently being read. */
  private static final class OpenZip {
    private final ZipInputStream stream;
    private final String path;
    private boolean hasEntries;

    OpenZip(ZipInputStream stream, String path) {
      this.stream = stream;
      this.path = path;
    }

    ZipInputStream stream() {
      return stream;
    }

    String path() {
      return path;
    }
  }

  /** A plain file. */
  private record FileSource(Path file, String displayPath) implements CimSource {
    @Override
    public InputStream openStream() throws IOException {
      return new BufferedInputStream(Files.newInputStream(file));
    }
  }

  /** The current entry of an open archive. */
  private record ZipEntrySource(ZipInputStream zip, String displayPath) implements CimSource {
    @Override
    public InputStream openStream() {
      return new NonClosingInputStream(zip);
    }
  }

  /** Shields the archive stream from being closed by readers of a single entry. */
  private static final class NonClosingInputStream extends FilterInputStream {
    NonClosingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public void close() {
      // The iterator closes the archive
    }
  }
}
