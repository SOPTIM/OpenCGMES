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

package de.soptim.opencgmes.cimreader.query.api;

import de.soptim.opencgmes.cimreader.core.exception.ImportException;
import de.soptim.opencgmes.cimreader.query.dataset.exception.ConfigurationException;
import de.soptim.opencgmes.cimreader.query.dataset.exception.DatasetNotLoadedException;
import de.soptim.opencgmes.cimreader.query.dataset.exception.ImportInProgressException;
import de.soptim.opencgmes.cimreader.query.dataset.exception.NoContentException;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QueryCancelledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DatasetNotLoadedException.class)
  public ProblemDetail handleDatasetNotLoaded(DatasetNotLoadedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(NoContentException.class)
  public ProblemDetail handleNoContent(NoContentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NO_CONTENT, e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
  public ProblemDetail handleBadRequest(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(ImportException.class)
  public ProblemDetail handleImport(ImportException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
  }

  @ExceptionHandler(ImportInProgressException.class)
  public ProblemDetail handleImportInProgress(ImportInProgressException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(QueryCancelledException.class)
  public ProblemDetail handleTimeout(QueryCancelledException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Query timed out");
  }

  @ExceptionHandler(ConfigurationException.class)
  public ProblemDetail handleConfiguration(ConfigurationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e) {
    log.error("Unexpected error", e);
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
        "Internal server error");
  }

}
