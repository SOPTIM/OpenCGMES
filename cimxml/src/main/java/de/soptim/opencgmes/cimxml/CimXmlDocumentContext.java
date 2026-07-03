/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimxml;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;

/**
 * The context of a CIMXML document: full model, difference model, or one of the named graphs in a
 * difference model.
 */
public enum CimXmlDocumentContext {
  fullModel,
  body,
  differenceModel,
  forwardDifferences,
  reverseDifferences,
  preconditions;

  /**
   * Get the graph name (Node) for the given context.
   *
   * @param context the context
   * @return the graph name (Node)
   */
  public static Node getGraphName(CimXmlDocumentContext context) {
    return switch (context) {
      case fullModel -> CimHeaderVocabulary.TYPE_FULL_MODEL;
      case body -> Quad.defaultGraphIRI;
      case differenceModel -> CimHeaderVocabulary.TYPE_DIFFERENCE_MODEL;
      case forwardDifferences -> CimHeaderVocabulary.GRAPH_FORWARD_DIFFERENCES;
      case reverseDifferences -> CimHeaderVocabulary.GRAPH_REVERSE_DIFFERENCES;
      case preconditions -> CimHeaderVocabulary.GRAPH_PRECONDITIONS;
    };
  }
}
