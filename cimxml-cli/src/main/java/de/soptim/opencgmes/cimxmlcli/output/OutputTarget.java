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

import java.nio.file.Path;
import java.util.Locale;

/**
 * The output file and its format, derived from the file name.
 *
 * @param path the output file
 * @param gzip whether the file is gzip-compressed ({@code .jsonld.gz})
 */
public record OutputTarget(Path path, boolean gzip) {

  private static final String JSONLD = ".jsonld";
  private static final String JSONLD_GZ = ".jsonld.gz";

  /**
   * Returns the target for {@code path}.
   *
   * @throws IllegalArgumentException if the file name ends neither with {@code .jsonld} nor with
   *     {@code .jsonld.gz}
   */
  public static OutputTarget of(Path path) {
    final var fileName = path.getFileName();
    final var name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(JSONLD_GZ) && name.length() > JSONLD_GZ.length()) {
      return new OutputTarget(path, true);
    }
    if (name.endsWith(JSONLD) && name.length() > JSONLD.length()) {
      return new OutputTarget(path, false);
    }
    throw new IllegalArgumentException(
        "'" + path + "' must end with " + JSONLD + " or " + JSONLD_GZ);
  }
}
