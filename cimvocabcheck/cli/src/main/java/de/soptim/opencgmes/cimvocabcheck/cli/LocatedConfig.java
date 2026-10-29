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

import de.soptim.opencgmes.cimvocabcheck.core.config.CimvocabcheckConfig;
import de.soptim.opencgmes.cimvocabcheck.core.config.ConfigLoader;
import java.nio.file.Path;

/**
 * A config and the directory its relative paths resolve against — the config file's own, which is
 * not the working directory when the file was discovered further up.
 *
 * @param config the parsed config, or {@code null} when there is none
 * @param base the directory relative schema paths resolve against
 */
record LocatedConfig(CimvocabcheckConfig config, Path base) {

  /** The explicit {@code configFile}, else the nearest {@code opencgmes.jsonc} above the CWD. */
  static LocatedConfig of(Path configFile) throws ConfigLoader.ConfigException {
    Path file =
        configFile != null ? configFile : ConfigLoader.discoverFile(Path.of(".")).orElse(null);
    if (file == null) {
      return new LocatedConfig(null, Path.of(".").toAbsolutePath());
    }
    return new LocatedConfig(ConfigLoader.load(file), file.toAbsolutePath().getParent());
  }
}
