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

package de.soptim.opencgmes.cimvocabcheck.cli;

import picocli.CommandLine;

/**
 * The tool version reported by {@code --version} and by the machine-readable reports.
 *
 * <p>Release versions are derived from git tags and stamped into the packaged JAR's manifest, so
 * the manifest is the only runtime source of truth. Runs from a checkout or an IDE have no
 * manifest; they report {@link #UNKNOWN} rather than a literal that would silently go stale.
 */
public final class ToolVersion implements CommandLine.IVersionProvider {

  /** Reported when no packaged version is available. */
  public static final String UNKNOWN = "unknown";

  /** Returns the running tool version, or {@link #UNKNOWN}. */
  public static String current() {
    Package pkg = ToolVersion.class.getPackage();
    String version = pkg == null ? null : pkg.getImplementationVersion();
    return version == null || version.isBlank() ? UNKNOWN : version;
  }

  @Override
  public String[] getVersion() {
    return new String[] {"cimvocabcheck " + current()};
  }
}
