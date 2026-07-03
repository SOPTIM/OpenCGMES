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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.jena.graph.Graph;

/**
 * A registry for mapping CIM namespaces to factory functions that create {@link CimProfile}
 * instances for those namespaces. This allows for dynamic registration and retrieval of profile
 * factories based on the CIM namespace of a graph.
 */
public class CimNamespaceFactoryRegistry {

  public static final String NAMESPACE = "namespace";
  private static final Map<String, Function<Graph, CimProfile>> map;

  static {
    map = new ConcurrentHashMap<>();
    map.put(CimProfile16.CIM_NAMESPACE, CimProfile16::new);
    map.put(CimProfile17.CIM_NAMESPACE, CimProfile17::new);
    map.put(CimProfile18.CIM_NAMESPACE, CimProfile18::new);
  }

  /**
   * Registers a factory function for creating {@link CimProfile} instances for the given
   * CIM namespace.
   *
   * @param namespace the CIM namespace for which the factory should be registered
   * @param factory   a function that takes a {@link Graph} and returns a {@link CimProfile}
   * @throws NullPointerException if either the namespace or the factory is null
   */
  public static void registerProfileFactory(String namespace, Function<Graph, CimProfile> factory) {
    Objects.requireNonNull(namespace, NAMESPACE);
    Objects.requireNonNull(factory, "constructor");
    map.put(namespace, factory);
  }

  /**
   * Unregisters the factory function associated with the given CIM namespace.
   *
   * @param namespace the CIM namespace for which the factory should be unregistered
   * @throws NullPointerException if the namespace is null
   */
  public static void unregisterProfileFactory(String namespace) {
    Objects.requireNonNull(namespace, NAMESPACE);
    map.remove(namespace);
  }

  /**
   * Retrieves the factory function associated with the given CIM namespace.
   *
   * @param namespace the CIM namespace for which to retrieve the factory
   * @return a function that takes a {@link Graph} and returns a {@link CimProfile},
   *     or null if no factory is registered for the given namespace
   * @throws NullPointerException if the namespace is null
   */
  public static Function<Graph, CimProfile> getProfileFactory(String namespace) {
    Objects.requireNonNull(namespace, NAMESPACE);
    return map.get(namespace);
  }

  /**
   * Checks if a factory function is registered for the given CIM namespace.
   *
   * @param namespace the CIM namespace to check
   * @return true if a factory function is registered for the given namespace, false otherwise
   * @throws NullPointerException if the namespace is null
   */
  public static boolean hasProfileFactory(String namespace) {
    Objects.requireNonNull(namespace, NAMESPACE);
    return map.containsKey(namespace);
  }
}
