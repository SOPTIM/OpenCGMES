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

package de.soptim.opencgmes.cimcheck.lsp.schema;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EndpointGraphFetcherTest {

    @Test
    public void rewritesFusekiUpdateEndpointToQuery() {
        // A Fuseki /update endpoint only accepts POST updates; reading the schema needs the /query sibling.
        assertEquals("http://localhost:3030/svedala/query",
                EndpointGraphFetcher.toQueryEndpoint("http://localhost:3030/svedala/update"));
    }

    @Test
    public void rewritesUpdateEndpointWithTrailingSlash() {
        assertEquals("http://localhost:3030/svedala/query",
                EndpointGraphFetcher.toQueryEndpoint("http://localhost:3030/svedala/update/"));
    }

    @Test
    public void leavesQueryEndpointUnchanged() {
        assertEquals("http://localhost:3030/svedala/query",
                EndpointGraphFetcher.toQueryEndpoint("http://localhost:3030/svedala/query"));
    }

    @Test
    public void leavesPlainDatasetEndpointUnchanged() {
        assertEquals("http://localhost:3030/svedala",
                EndpointGraphFetcher.toQueryEndpoint("http://localhost:3030/svedala"));
    }

    @Test
    public void doesNotRewriteWhenUpdateIsNotTheLastSegment() {
        // "update" embedded in a dataset name, not the Fuseki service suffix.
        assertEquals("http://localhost:3030/update/query",
                EndpointGraphFetcher.toQueryEndpoint("http://localhost:3030/update/query"));
    }

    @Test
    public void toleratesNull() {
        assertNull(EndpointGraphFetcher.toQueryEndpoint(null));
    }
}
