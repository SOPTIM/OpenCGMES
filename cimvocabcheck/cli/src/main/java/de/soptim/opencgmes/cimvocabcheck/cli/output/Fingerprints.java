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
import org.apache.jena.graph.Node;

/**
 * Assigns each finding of a run a stable fingerprint, so consumers that track findings across runs
 * (GitLab Code Quality, GitHub code scanning) can recognise the same finding again.
 *
 * <p>The fingerprint is shared by every machine-readable report format: one finding has one
 * identity regardless of which format it was rendered into.
 *
 * <p>An instance is scoped to a single report. It counts the findings already seen with the same
 * identity, so repeated findings get distinct fingerprints that depend on report order.
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
   * The stable identity of a finding: file, rule and offending term.
   *
   * <p>The position is left out, so an edit above a finding does not turn it into a new one.
   * Repeated findings are told apart by their occurrence index instead, which follows report order.
   * {@code ValidateCommand} sorts that into source order, so the index only shifts when an equal
   * finding earlier in the file comes or goes.
   *
   * <p>The message is left out too: rewording it, or listing one more profile, must not turn the
   * same finding into a new one.
   */
  private static String identity(String path, SparqlValidationAnnotation a) {
    return String.join(" ", path, a.code().name(), termKey(a.term()));
  }

  /** A run-independent spelling of the offending term; blank node labels are not. */
  private static String termKey(Node term) {
    if (term == null) {
      return "";
    }
    if (term.isURI()) {
      return term.getURI();
    }
    if (term.isVariable()) {
      return "?" + term.getName();
    }
    if (term.isLiteral()) {
      return term.getLiteralLexicalForm();
    }
    return "";
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
