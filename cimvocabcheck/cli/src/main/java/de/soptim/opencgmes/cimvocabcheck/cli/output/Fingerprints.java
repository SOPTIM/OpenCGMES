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

package de.soptim.opencgmes.cimvocabcheck.cli.output;

import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Assigns each finding of a run a stable fingerprint, so consumers that track findings across runs
 * (GitLab Code Quality, GitHub code scanning) can recognise the same finding again.
 *
 * <p>The fingerprint is shared by every machine-readable report format: one finding has one
 * identity regardless of which format it was rendered into.
 *
 * <p>An instance is scoped to a single report — it counts how many findings sharing the same
 * identity have already been emitted, so genuinely duplicate findings get distinct fingerprints
 * independent of the order they appear in.
 */
final class Fingerprints {

  private final Map<String, Integer> occurrences = new HashMap<>();

  /** Returns the fingerprint of one finding at {@code path}. */
  String of(String path, SparqlValidationAnnotation annotation) {
    String identity = identity(path, annotation);
    int occurrence = occurrences.merge(identity, 1, Integer::sum) - 1;
    return sha256Hex(identity + ' ' + occurrence);
  }

  /** Strips a leading {@code ./} so one file yields one path spelling. */
  static String normalizePath(String path) {
    return path.startsWith("./") ? path.substring(2) : path;
  }

  /**
   * The stable identity of a finding: file, rule, position and offending term. Deliberately
   * excludes the rendered message — its profile list is not emitted in a deterministic order, so
   * folding it into the fingerprint would make the same finding churn between otherwise-identical
   * runs and break the consumer's ability to track it over time.
   */
  private static String identity(String path, SparqlValidationAnnotation a) {
    String term = a.term() != null && a.term().isURI() ? a.term().getURI() : "";
    return String.join(
        " ", path, a.code().name(), String.valueOf(a.line()), String.valueOf(a.column()), term);
  }

  private static String sha256Hex(String input) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    var sb = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
