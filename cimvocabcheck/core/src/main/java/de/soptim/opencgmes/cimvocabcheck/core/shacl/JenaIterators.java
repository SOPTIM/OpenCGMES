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

package de.soptim.opencgmes.cimvocabcheck.core.shacl;

/** Small helpers for working with Jena graph iterators. */
final class JenaIterators {

  private JenaIterators() {}

  /**
   * Closes {@code it} if it is {@link AutoCloseable}, swallowing any close failure. Jena's {@code
   * ExtendedIterator}s are {@code AutoCloseable}; closing them in a {@code finally} releases their
   * backing resources.
   */
  static void closeQuietly(Object it) {
    if (it instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // Intentionally ignored.
      }
    }
  }
}
