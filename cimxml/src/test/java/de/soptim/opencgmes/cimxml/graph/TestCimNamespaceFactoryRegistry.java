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

package de.soptim.opencgmes.cimxml.graph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class TestCimNamespaceFactoryRegistry {

    private static final String CUSTOM_NAMESPACE = "https://example.org/test-custom-cim#";

    @After
    public void unregister() {
        CimNamespaceFactoryRegistry.unregisterProfileFactory(CUSTOM_NAMESPACE);
    }

    @Test
    public void registeredNamespacesIncludesTheThreeBuiltIns() {
        var namespaces = CimNamespaceFactoryRegistry.registeredNamespaces();

        assertTrue(namespaces.contains(CimProfile16.CIM_NAMESPACE));
        assertTrue(namespaces.contains(CimProfile17.CIM_NAMESPACE));
        assertTrue(namespaces.contains(CimProfile18.CIM_NAMESPACE));
    }

    @Test
    public void registeredNamespacesReflectsCustomRegistrations() {
        assertFalse(CimNamespaceFactoryRegistry.registeredNamespaces().contains(CUSTOM_NAMESPACE));

        CimNamespaceFactoryRegistry.registerProfileFactory(CUSTOM_NAMESPACE, CimProfile17::new);

        assertTrue(CimNamespaceFactoryRegistry.registeredNamespaces().contains(CUSTOM_NAMESPACE));
        assertTrue(CimNamespaceFactoryRegistry.hasProfileFactory(CUSTOM_NAMESPACE));

        CimNamespaceFactoryRegistry.unregisterProfileFactory(CUSTOM_NAMESPACE);

        assertFalse(CimNamespaceFactoryRegistry.registeredNamespaces().contains(CUSTOM_NAMESPACE));
    }
}
