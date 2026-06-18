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

package de.soptim.opencgmes.cimcheck.core.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Covers the Fuseki {@code update}→{@code query} sibling derivation used as a 405 fallback. */
public class HttpSparqlGraphSourceTest {

  @Test
  public void derivesQuerySiblingForFusekiUpdateEndpoint() {
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/update"));
  }

  @Test
  public void derivesQuerySiblingForUpdateEndpointWithTrailingSlash() {
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/update/"));
  }

  @Test
  public void derivesQuerySiblingForFusekiShaclEndpoint() {
    // The workshop notebook points SHACL cells at Fuseki's /shacl operation; for schema loading
    // we must read from the dataset's /query service instead.
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/shacl"));
  }

  @Test
  public void stripsQueryStringWhenDerivingSibling() {
    // A Fuseki SHACL/GSP "?graph=..." must be dropped — it is meaningless to a SPARQL query.
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling(
            "http://localhost:3030/test/shacl?graph=cgmes:model:SSH"));
  }

  @Test
  public void derivesQuerySiblingForGspOperations() {
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/data"));
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/get"));
    assertEquals(
        "http://localhost:3030/test/query",
        HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/upload"));
  }

  @Test
  public void hasNoSiblingForQueryEndpoint() {
    assertNull(HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/query"));
  }

  @Test
  public void hasNoSiblingForSparqlEndpoint() {
    assertNull(HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test/sparql"));
  }

  @Test
  public void hasNoSiblingForPlainDatasetEndpoint() {
    assertNull(HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/test"));
  }

  @Test
  public void hasNoSiblingWhenUpdateIsNotTheLastSegment() {
    assertNull(HttpSparqlGraphSource.queryEndpointSibling("http://localhost:3030/update/query"));
  }

  @Test
  public void toleratesNull() {
    assertNull(HttpSparqlGraphSource.queryEndpointSibling(null));
  }
}
