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

package de.soptim.opencgmes.cimvocabcheck.core;

/**
 * Stable codes for failures at the schema-loading stage, attached to {@link
 * CgmesSchemaLoader.SchemaLoadException}. Unlike {@link SparqlValidationCode} (which annotates a
 * position in a validated file), these classify why a schema failed to load in the first place.
 */
public enum SchemaLoadCode {
  /**
   * One or more sources declared a {@code cim} namespace that has no {@link
   * de.soptim.opencgmes.cimxml.graph.CimNamespaceFactoryRegistry} entry — the profile shape is
   * unknown, so the source could not be parsed. Register the namespace (e.g. via the {@code
   * cimNamespaces} setting in {@code opencgmes.json}) to resolve this.
   */
  UNRECOGNIZED_CIM_NAMESPACE,
  /**
   * No source contained a recognizable CIM profile — either none declared a {@code cim} namespace
   * at all, or parsing failed for reasons unrelated to the namespace (malformed RDF, missing
   * version IRI, etc.).
   */
  NO_CIM_PROFILES_FOUND
}
