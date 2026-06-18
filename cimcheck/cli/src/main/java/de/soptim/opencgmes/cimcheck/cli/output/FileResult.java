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

package de.soptim.opencgmes.cimcheck.cli.output;

import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationSeverity;
import java.util.List;

/** Per-file validation result ready for formatting — no Jena Node types, pure data. */
public record FileResult(
    String source, boolean valid, List<SparqlValidationAnnotation> annotations) {
  /** Returns the number of ERROR-severity annotations. */
  public long errorCount() {
    return annotations.stream().filter(a -> a.severity() == SparqlValidationSeverity.ERROR).count();
  }

  /** Returns the number of WARN-severity annotations. */
  public long warnCount() {
    return annotations.stream().filter(a -> a.severity() == SparqlValidationSeverity.WARN).count();
  }
}
