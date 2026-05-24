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

/**
 * Thrown by the dependency-analysis methods of {@link SparqlValidationApi} when the query cannot
 * be parsed at all.
 *
 * <p>{@link SparqlValidationApi#validateSparql(String)} and its overloads prefer to return the
 * syntax error as a {@code SYNTAX_ERROR} annotation inside a {@link SparqlValidationResult} and
 * do <em>not</em> throw this exception.</p>
 */
public class InvalidQueryException extends Exception {

    private final Integer line;
    private final Integer column;

    public InvalidQueryException(String message, Throwable cause, Integer line, Integer column) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    public InvalidQueryException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    /** @return 1-based parser line, or {@code null} if unavailable. */
    public Integer line() {
        return line;
    }

    /** @return 1-based parser column, or {@code null} if unavailable. */
    public Integer column() {
        return column;
    }
}
