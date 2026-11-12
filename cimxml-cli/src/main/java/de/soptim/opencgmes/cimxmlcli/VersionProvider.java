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

package de.soptim.opencgmes.cimxmlcli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/** Provides the tool version from {@code version.properties}, filtered by the Maven build. */
public final class VersionProvider implements IVersionProvider {

  private static final String RESOURCE = "/version.properties";

  @Override
  public String[] getVersion() {
    return new String[] {"cimxml2jsonld " + version()};
  }

  /** Returns the version of this tool, or {@code "unknown"} if it cannot be determined. */
  static String version() {
    try (var in = VersionProvider.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return "unknown";
      }
      final var properties = new Properties();
      properties.load(in);
      return properties.getProperty("version", "unknown");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }
}
