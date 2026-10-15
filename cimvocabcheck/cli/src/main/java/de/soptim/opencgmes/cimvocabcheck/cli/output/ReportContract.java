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

/**
 * Constants of the machine-readable report contract that non-Java consumers bind to.
 *
 * <p>{@link #VERSION} versions the report document itself, independently of the tool version: a
 * minor bump adds fields, output formats or rule codes (consumers must ignore what they do not
 * know), a major bump is required to rename or remove anything. The JSON report shape is published
 * as a JSON Schema under {@code cimvocabcheck/schemas/}.
 */
final class ReportContract {

  /** Version of the report contract — {@code "<major>.<minor>"}. */
  static final String VERSION = "1.0";

  /** Tool name carried by every machine-readable report. */
  static final String TOOL_NAME = "cimvocabcheck";

  /** Product documentation, reported as the SARIF driver's {@code informationUri}. */
  static final String INFORMATION_URI = "https://opencgmes.soptim.de/cimvocabcheck/overview";

  /** Canonical catalogue of the rule codes, reported as every SARIF rule's {@code helpUri}. */
  static final String RULES_URI = "https://opencgmes.soptim.de/cimvocabcheck/validation-checks";

  private ReportContract() {}
}
