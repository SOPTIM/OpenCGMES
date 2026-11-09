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

package de.soptim.opencgmes.cimxmlcli.schema;

import de.soptim.opencgmes.cimxml.graph.CimProfile;

/**
 * An RDFS profile together with the source it was loaded from.
 *
 * @param displayPath path of the source, see {@link
 *     de.soptim.opencgmes.cimxmlcli.source.CimSource#displayPath()}
 * @param profile the registered profile
 */
public record LoadedSchema(String displayPath, CimProfile profile) {}
