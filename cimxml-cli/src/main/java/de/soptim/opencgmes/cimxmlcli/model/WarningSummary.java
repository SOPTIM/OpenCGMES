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

package de.soptim.opencgmes.cimxmlcli.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parser warnings of one source, grouped by kind.
 *
 * @param countsByKind number of warnings per kind, most frequent first
 * @param messages all individual warning messages, for verbose output
 */
public record WarningSummary(Map<String, Integer> countsByKind, List<String> messages) {

  /** A summary without warnings. */
  public static final WarningSummary NONE = new WarningSummary(Map.of(), List.of());

  /** Creates a summary. */
  public WarningSummary {
    countsByKind = Collections.unmodifiableMap(new LinkedHashMap<>(countsByKind));
    messages = List.copyOf(messages);
  }

  /** Returns the total number of warnings. */
  public int total() {
    return messages.size();
  }
}
