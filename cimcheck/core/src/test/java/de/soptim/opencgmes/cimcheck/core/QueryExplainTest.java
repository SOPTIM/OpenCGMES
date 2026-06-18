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

package de.soptim.opencgmes.cimcheck.core;

import static org.junit.Assert.*;

import de.soptim.opencgmes.cimcheck.core.explain.QueryExplanation;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link SparqlValidationApi#explain(String)} / {@code explainStatic}. */
public class QueryExplainTest {

  private static final String CIM = "http://iec.ch/TC57/CIM100#";
  private static final String CLASS_AC_LINE = CIM + "ACLineSegment";
  private static final String PROP_R = CIM + "ACLineSegment.r";
  private static final String PROFILE_EQ = "http://example.org/profile/Equipment/1.0";

  private SparqlValidationApi api;

  @Before
  public void setUp() {
    RdfsSchemaIndex index =
        RdfsSchemaIndex.builder()
            .addProfile(PROFILE_EQ, List.of(CLASS_AC_LINE), List.of(PROP_R))
            .build();
    api = new SparqlValidationApi(index);
  }

  @Test
  public void selectWithFilterHasBothAlgebraForms() {
    QueryExplanation ex =
        api.explain(
            "SELECT * WHERE { ?s a cim:ACLineSegment ; cim:ACLineSegment.r ?r . FILTER(?r > 1) }");
    assertTrue("should have a plan", ex.hasPlan());
    assertNotNull(ex.algebra());
    assertNotNull(ex.optimizedAlgebra());
    assertTrue("plan should mention a BGP", ex.algebra().toLowerCase().contains("bgp"));
    // render() should produce the sectioned output.
    String rendered = ex.render();
    assertTrue(rendered.contains("# Algebra"));
    assertTrue(rendered.contains("# Algebra (optimized)"));
  }

  @Test
  public void defaultPrefixesAreInjected() {
    // No explicit PREFIX line for cim: — injection must make it parse.
    QueryExplanation ex = api.explain("SELECT * WHERE { ?s a cim:ACLineSegment }");
    assertTrue(ex.hasPlan());
  }

  @Test
  public void updateRequestHasNoPlan() {
    QueryExplanation ex = api.explain("INSERT DATA { <urn:s> <urn:p> <urn:o> }");
    assertFalse(ex.hasPlan());
    assertNotNull(ex.note());
    assertTrue(ex.note().toLowerCase().contains("update"));
  }

  @Test
  public void brokenInputIsHandledGracefully() {
    QueryExplanation ex = api.explain("SELECT WHERE WHERE {{{");
    assertFalse(ex.hasPlan());
    assertNotNull(ex.note());
  }

  @Test
  public void explainStaticWorksWithoutSchema() {
    QueryExplanation ex = SparqlValidationApi.explainStatic("SELECT * WHERE { ?s ?p ?o }");
    assertTrue(ex.hasPlan());
    assertNotNull(ex.optimizedAlgebra());
  }
}
