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

package de.soptim.opencgmes.cimvocabcheck.lsp.notebook;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates a SHACL cell against a remote SHACL service (Fuseki's {@code …/shacl} operation and
 * compatibles): the cell's shapes are POSTed as {@code text/turtle} and the service validates its
 * own data against them, answering with a validation report as Turtle. Any {@code ?graph=…} query
 * parameter on the {@link ExecuteTarget#shaclUrl()} travels as-is, so a directive can pin the graph
 * to validate.
 *
 * <p>The blocking HTTP call runs under the usual cancellation race ({@link
 * ExecSupport#runCancellable}); the request timeout is enforced by the HTTP client itself.
 */
final class HttpShaclClient {

  private static final Logger LOG = LoggerFactory.getLogger(HttpShaclClient.class);

  private static final String TEXT_TURTLE = "text/turtle";

  /**
   * One client for all SHACL requests. An {@link HttpClient} owns a selector thread and an
   * executor, and is only released once it becomes unreachable — a per-request client would leave
   * those threads behind on every SHACL cell run. The timeout is per-request ({@link
   * HttpRequest#timeout()}), so nothing here needs to be per-execution.
   */
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private HttpShaclClient() {}

  /** Validates {@code shapesTurtle} against the target's SHACL service. */
  static ExecuteResponse validate(
      String shapesTurtle, ExecuteTarget target, ExecuteOptions options, ExecContext ctx) {
    String shaclUrl = target.shaclUrl();
    if (ExecSupport.isBlank(shaclUrl)) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.NO_TARGET,
              "This endpoint has no SHACL validation service configured.",
              null,
              null,
              null));
    }

    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(URI.create(shaclUrl))
              .timeout(Duration.ofMillis(ExecSupport.timeoutMs(options)))
              .header("Content-Type", TEXT_TURTLE)
              .header("Accept", TEXT_TURTLE)
              .POST(HttpRequest.BodyPublishers.ofString(shapesTurtle))
              .build();
    } catch (IllegalArgumentException e) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.HTTP_ERROR, "Invalid SHACL service URL: " + shaclUrl, null, null, null));
    }
    return ExecSupport.runCancellable(ctx, () -> {}, () -> send(request, shaclUrl));
  }

  /** Runs on {@code ctx.executionPool()}. */
  private static ExecuteResponse send(HttpRequest request, String shaclUrl) {
    long start = System.currentTimeMillis();
    try {
      HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      return toResponse(response, start, shaclUrl);
    } catch (HttpTimeoutException e) {
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.TIMEOUT, "Request timed out.", e.getMessage(), null, null));
    } catch (IOException e) {
      return ExecuteResponse.failed(
          new ExecError(ErrorCode.HTTP_ERROR, e.getMessage(), null, null, null));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.debug("SHACL request interrupted after cancellation");
      return ExecuteResponse.cancelled();
    }
  }

  private static ExecuteResponse toResponse(
      HttpResponse<String> response, long startMs, String shaclUrl) {
    int status = response.statusCode();
    String body = response.body();
    if (status == 401 || status == 403) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.AUTH_FAILED,
              "Authentication failed (HTTP " + status + ").",
              trimmed(body),
              null,
              null));
    }
    if (status < 200 || status >= 300) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.HTTP_ERROR,
              "SHACL service answered HTTP " + status + ".",
              trimmed(body),
              null,
              null));
    }

    ValidationReport report;
    try {
      Model model = ModelFactory.createDefaultModel();
      model.read(new StringReader(body), null, "TURTLE");
      report = ValidationReport.fromModel(model);
    } catch (RuntimeException e) {
      return ExecuteResponse.failed(
          new ExecError(
              ErrorCode.HTTP_ERROR,
              "SHACL service returned a report that could not be parsed: " + e.getMessage(),
              trimmed(body),
              null,
              null));
    }
    // Keep the service's own serialization as the payload — it is what the user's endpoint said.
    return ShaclExecutor.toResponse(report, body, startMs, shaclUrl);
  }

  /** Body excerpt for error details — services can answer with whole HTML error pages. */
  private static String trimmed(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    String trimmed = body.strip();
    return trimmed.length() <= 1_000 ? trimmed : trimmed.substring(0, 1_000) + "…";
  }
}
