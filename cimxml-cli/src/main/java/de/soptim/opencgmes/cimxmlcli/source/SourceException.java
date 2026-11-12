/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimxmlcli.source;

import de.soptim.opencgmes.cimxmlcli.validation.Problem;
import java.io.Serial;

/** Thrown when a single source cannot be read. The conversion continues with the next source. */
public final class SourceException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  private final Problem problem;

  /** Creates the exception for {@code problem}. */
  public SourceException(Problem problem, Throwable cause) {
    super(problem.format(), cause);
    this.problem = problem;
  }

  /** Creates the exception for {@code problem}. */
  public SourceException(Problem problem) {
    this(problem, null);
  }

  /** Returns the problem to report. */
  public Problem getProblem() {
    return problem;
  }
}
