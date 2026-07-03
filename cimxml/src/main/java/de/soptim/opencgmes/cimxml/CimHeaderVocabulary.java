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
import org.apache.jena.graph.NodeFactory;

/**
 * Vocabulary for CIMXML header information in RDF.
 */
public final class CimHeaderVocabulary {

  public static final String NS_MD = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
  public static final String NS_DM = "http://iec.ch/TC57/61970-552/DifferenceModel/1#";
  public static final String CLASSNAME_FULL_MODEL = "FullModel";
  public static final String CLASSNAME_DIFFERENCE_MODEL = "DifferenceModel";
  public static final String TAG_NAME_FORWARD_DIFFERENCES = "forwardDifferences";
  public static final String TAG_NAME_REVERSE_DIFFERENCES = "reverseDifferences";
  public static final String TAG_NAME_PRECONDITIONS = "preconditions";

  public static final Node PREDICATE_PROFILE = NodeFactory.createURI(NS_MD + "Model.profile");
  public static final Node PREDICATE_SUPERSEDES = NodeFactory.createURI(NS_MD + "Model.Supersedes");
  public static final Node PREDICATE_DEPENDENT_ON
      = NodeFactory.createURI(NS_MD + "Model.DependentOn");

  public static final String FULL_MODEL_URI = NS_MD + CLASSNAME_FULL_MODEL;
  public static final Node TYPE_FULL_MODEL = NodeFactory.createURI(FULL_MODEL_URI);
  public static final String TYPE_DIFFERENCE_MODEL_URI = NS_DM + CLASSNAME_DIFFERENCE_MODEL;
  public static final Node TYPE_DIFFERENCE_MODEL = NodeFactory.createURI(TYPE_DIFFERENCE_MODEL_URI);
  public static final String GRAPH_FORWARD_DIFFERENCES_URI = NS_DM + TAG_NAME_FORWARD_DIFFERENCES;
  public static final Node GRAPH_FORWARD_DIFFERENCES
      = NodeFactory.createURI(GRAPH_FORWARD_DIFFERENCES_URI);
  public static final String GRAPH_REVERSE_DIFFERENCES_URI = NS_DM + TAG_NAME_REVERSE_DIFFERENCES;
  public static final Node GRAPH_REVERSE_DIFFERENCES
      = NodeFactory.createURI(GRAPH_REVERSE_DIFFERENCES_URI);
  public static final String GRAPH_PRECONDITIONS_URI = NS_DM + TAG_NAME_PRECONDITIONS;
  public static final Node GRAPH_PRECONDITIONS = NodeFactory.createURI(GRAPH_PRECONDITIONS_URI);

  private CimHeaderVocabulary() {
    // prevent instantiation
  }
}
