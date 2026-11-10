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

package de.soptim.opencgmes.cimxmlcli.dataset;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/** The graph naming convention of the dataset: {@code cgmes:<kind>:<profile keyword>}. */
public final class GraphNames {

  /** Prefix of the graphs holding the RDFS profiles. */
  public static final String SCHEMA_PREFIX = "cgmes:schema:";

  /** Prefix of the graphs holding the md:FullModel headers. */
  public static final String HEADER_PREFIX = "cgmes:header:";

  /** Prefix of the graphs holding the model bodies. */
  public static final String MODEL_PREFIX = "cgmes:model:";

  private GraphNames() {}

  /** Returns {@code cgmes:schema:<keyword>}. */
  public static Node schema(String keyword) {
    return NodeFactory.createURI(SCHEMA_PREFIX + keyword);
  }

  /** Returns {@code cgmes:header:<keyword>}. */
  public static Node header(String keyword) {
    return NodeFactory.createURI(HEADER_PREFIX + keyword);
  }

  /** Returns {@code cgmes:model:<keyword>}. */
  public static Node model(String keyword) {
    return NodeFactory.createURI(MODEL_PREFIX + keyword);
  }
}
