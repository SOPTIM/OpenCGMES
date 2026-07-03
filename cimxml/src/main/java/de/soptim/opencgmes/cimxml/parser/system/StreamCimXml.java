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

package de.soptim.opencgmes.cimxml.parser.system;

import de.soptim.opencgmes.cimxml.CimXmlDocumentContext;
import de.soptim.opencgmes.cimxml.graph.CimModelHeader;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import org.apache.jena.riot.system.StreamRDF;

/**
 * An extension of {@link StreamRDF} to provide access to the underlying {@link CimDatasetGraph} and
 * to store additional information about the CIMXML file being processed.
 */
public interface StreamCimXml extends StreamRDF {

  /**
   * Gets the underlying {@link CimDatasetGraph} that is being populated.
   */
  CimDatasetGraph getCimDatasetGraph();

  /**
   * Gets the model header information as found in the CIMXML file.
   */
  CimModelHeader getModelHeader();

  /**
   * Gets the preprocessing instruction version of IEC 61970-552.
   *
   * @return the version string as found in the CIMXML file
   */
  String getVersionOfIec61970_552();

  /**
   * Sets the preprocessing instruction version of IEC 61970-552.
   *
   * @param versionOfCimXml the version string as found in the CIMXML file
   */
  void setVersionOfIec61970_552(String versionOfCimXml);

  /**
   * Gets the 'cim' namespace URI as found in the CIMXML file.
   *
   * @return the 'cim' namespace as found in the CIMXML file
   */
  String getCimNamespace();

  /**
   * Sets the 'cim' namespace URI as found in the CIMXML file.
   *
   * @param cimNamespace the 'cim' namespace URI as found in the CIMXML file
   */
  void setCimNamespace(String cimNamespace);

  /**
   * Gets the current document context.
   */
  CimXmlDocumentContext getCurrentContext();

  /**
   * Sets the current document context.
   *
   * @param context the current document context
   */
  void setCurrentContext(CimXmlDocumentContext context);
}