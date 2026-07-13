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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Stream;

/**
 * Glob expansion for {@code # [endpoint=...]} file directives, matching the SPARQL Notebook
 * extension's behavior: a directive value may be a glob pattern such as {@code ./rdf/*.ttl} or
 * {@code ./rdf/{a,b}.ttl} that stands for every file it matches.
 *
 * <p>The pattern language is {@link java.nio.file.FileSystem#getPathMatcher glob} syntax ({@code
 * *}, {@code ?}, {@code {a,b}}, {@code [...]}, and {@code **} for crossing directories). Directive
 * values contain no spaces, so backslashes are treated as path separators (Windows-style paths),
 * never as glob escapes.
 */
public final class FileGlobs {

  private FileGlobs() {}

  /** Directory levels {@code **} may descend into; plain patterns walk only their own depth. */
  private static final int MAX_RECURSIVE_DEPTH = 64;

  /** Whether a directive value is a glob pattern rather than a plain path. */
  public static boolean isPattern(String value) {
    if (value == null) {
      return false;
    }
    return value.chars().anyMatch(c -> c == '*' || c == '?' || c == '{' || c == '[');
  }

  /**
   * Expands a glob pattern to the regular files it matches, sorted by path. A relative pattern
   * resolves against {@code base}. Returns an empty list when nothing matches, when the pattern's
   * literal directory prefix does not exist, or when a relative pattern has no base.
   *
   * @throws java.util.regex.PatternSyntaxException if the pattern is not valid glob syntax
   */
  public static List<Path> expand(String pattern, Path base) {
    String normalized = pattern.replace('\\', '/');
    List<String> segments = List.of(normalized.split("/"));
    int firstMeta = 0;
    while (firstMeta < segments.size() && !isPattern(segments.get(firstMeta))) {
      firstMeta++;
    }
    String literal = String.join("/", segments.subList(0, firstMeta));
    String glob = String.join("/", segments.subList(firstMeta, segments.size()));
    if (glob.isEmpty()) {
      // Not actually a pattern — treat the whole value as one plain path.
      Path p = resolveRoot(normalized, base);
      return p != null && Files.isRegularFile(p) ? List.of(p) : List.of();
    }
    if (normalized.startsWith("/") && literal.isEmpty()) {
      literal = "/";
    }
    Path root = resolveRoot(literal, base);
    if (root == null || !Files.isDirectory(root)) {
      return List.of();
    }
    PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + glob);
    int depth =
        glob.contains("**")
            ? MAX_RECURSIVE_DEPTH
            : (int) glob.chars().filter(c -> c == '/').count() + 1;
    try (Stream<Path> walk = Files.walk(root, depth)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> matcher.matches(root.relativize(p)))
          .sorted()
          .toList();
    } catch (IOException | UncheckedIOException e) {
      return List.of();
    }
  }

  /**
   * The directory (or file) a pattern's literal prefix denotes, or {@code null} if unresolvable.
   */
  private static Path resolveRoot(String literal, Path base) {
    Path p = literal.isEmpty() ? null : Path.of(literal);
    if (p != null && p.isAbsolute()) {
      return p.normalize();
    }
    if (base == null) {
      return null;
    }
    return (p == null ? base : base.resolve(p)).normalize();
  }
}
