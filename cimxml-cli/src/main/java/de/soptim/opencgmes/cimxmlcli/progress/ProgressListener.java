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

package de.soptim.opencgmes.cimxmlcli.progress;

import de.soptim.opencgmes.cimxmlcli.model.ParsedModel;
import de.soptim.opencgmes.cimxmlcli.schema.LoadedSchema;
import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.nio.file.Path;
import java.time.Duration;

/** Receives progress events of a conversion. All methods do nothing by default. */
public interface ProgressListener {

  /** A listener that ignores all events. */
  ProgressListener NONE = new ProgressListener() {};

  /** Reading the RDFS profiles of {@code folder} starts. */
  default void schemaReadingStarted(Path folder) {}

  /** The {@code index}-th RDFS profile (1-based) was parsed. */
  default void schemaLoaded(int index, LoadedSchema schema) {}

  /** An issue that does not prevent the conversion, e.g. an ignored duplicate profile. */
  default void warning(Problem warning) {}

  /** Reading the CIMXML models of {@code folder} starts. */
  default void modelReadingStarted(Path folder) {}

  /** The {@code index}-th CIMXML model (1-based) was parsed in {@code time}. */
  default void modelParsed(int index, ParsedModel model, Duration time) {}

  /** Writing the dataset to {@code target} starts. */
  default void writingStarted(Path target) {}

  /** The dataset with {@code quads} quads was written: {@code bytes} in {@code time}. */
  default void writingFinished(Path target, long quads, long bytes, Duration time) {}
}
