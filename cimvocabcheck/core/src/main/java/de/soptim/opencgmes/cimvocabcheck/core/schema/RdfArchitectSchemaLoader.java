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

package de.soptim.opencgmes.cimvocabcheck.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a validation schema from the profiles held in an <a
 * href="https://github.com/SOPTIM/RDFArchitect">RDFArchitect</a> instance, so a workspace can
 * validate against the model as it is curated there instead of against files on disk.
 *
 * <p>The instance is read over its REST API — no SPARQL endpoint and no direct access to its store
 * are needed. Each graph of the dataset is exported as Turtle into an in-memory dataset, which is
 * then handed to {@link EndpointSchemaLoader}; profile detection, named-graph mapping and the
 * schema index are therefore exactly the same as for a SPARQL endpoint.
 *
 * <p>Datasets in RDFArchitect belong to a browser session. This loader gets its own session, so a
 * {@link RdfArchitectSource#snapshot() snapshot} — which any session can load — always works, while
 * a plain {@link RdfArchitectSource#dataset() dataset} is only visible when the instance is backed
 * by a triple store that persists datasets beyond a session.
 */
public final class RdfArchitectSchemaLoader {

  private static final Logger LOG = LoggerFactory.getLogger(RdfArchitectSchemaLoader.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** RDFArchitect's session cookie; its value is the session id an editor hands over. */
  public static final String SESSION_COOKIE = "RDFA_SESSION_ID";

  private RdfArchitectSchemaLoader() {}

  /**
   * Loads the schema addressed by {@code source} in a session of this loader's own.
   *
   * @param timeout per-request timeout
   * @throws RdfArchitectException when the instance cannot be reached or does not hold the
   *     requested dataset
   */
  public static EndpointSchema load(RdfArchitectSource source, Duration timeout) {
    return load(source, timeout, null);
  }

  /**
   * Loads the schema addressed by {@code source}, optionally borrowing a browser's session.
   *
   * <p>With a {@code sessionId}, the datasets read are the ones that session is editing — the
   * working copy behind an open RDFArchitect window, unsaved changes included. That is the only way
   * to see a dataset live: RDFArchitect keeps one working copy per session and never publishes it.
   * A snapshot needs no session and is deliberately never loaded into a borrowed one, because doing
   * so would add a dataset to somebody's open editor.
   *
   * @param sessionId the value of that session's {@code RDFA_SESSION_ID} cookie, or {@code null}
   * @param timeout per-request timeout
   * @throws RdfArchitectException when the instance cannot be reached or does not hold the
   *     requested dataset
   */
  public static EndpointSchema load(RdfArchitectSource source, Duration timeout, String sessionId) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(timeout, "timeout");
    Client client = clientFor(source, timeout, sessionId);

    String dataset = resolveDataset(client, source);
    List<String> graphs = client.listGraphs(dataset);
    LOG.info("RDFArchitect dataset {} exposes {} graph(s)", dataset, graphs.size());

    Dataset local = DatasetFactory.createTxnMem();
    for (String graph : graphs) {
      String turtle = client.fetchGraph(dataset, graph);
      Model model = ModelFactory.createDefaultModel();
      RDFParser.create().source(new StringReader(turtle)).lang(Lang.TURTLE).parse(model);
      local.addNamedModel(graph, model);
    }
    try (SparqlGraphSource graphSource = new DatasetSparqlGraphSource(local)) {
      return EndpointSchemaLoader.load(graphSource);
    } catch (Exception e) {
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new RdfArchitectException("Error closing RDFArchitect source: " + e.getMessage(), e);
    }
  }

  /**
   * A change stamp for the dataset behind {@code source}: it differs whenever a graph of that
   * dataset has been edited. Reading the per-graph change logs is far cheaper than exporting every
   * graph, so a live schema can be checked often and refetched only when it actually moved.
   *
   * @return an opaque stamp, or {@code null} when the dataset cannot be read at all
   */
  public static String changeStamp(RdfArchitectSource source, Duration timeout, String sessionId) {
    Objects.requireNonNull(source, "source");
    try {
      Client client = clientFor(source, timeout, sessionId);
      String dataset =
          source.snapshot() == null ? source.dataset() : resolveDataset(client, source);
      var stamp = new StringBuilder();
      for (String graph : client.listGraphs(dataset)) {
        stamp.append(graph).append('=').append(client.latestChangeId(dataset, graph)).append(';');
      }
      return stamp.toString();
    } catch (RuntimeException e) {
      LOG.debug("Could not read the change stamp of {}: {}", source.describe(), e.getMessage());
      return null;
    }
  }

  /**
   * A client for {@code source}: one that borrows {@code sessionId} when given, except for a
   * snapshot, which is loadable from any session and must not be dropped into a borrowed one.
   */
  private static Client clientFor(RdfArchitectSource source, Duration timeout, String sessionId) {
    boolean borrow = sessionId != null && !sessionId.isBlank() && source.snapshot() == null;
    return new Client(source.baseUrl(), timeout, borrow ? sessionId : null);
  }

  /**
   * The dataset to read: a snapshot is loaded into this loader's session first, which is what makes
   * it readable at all, and then names the dataset {@code SNAPSHOT_<name>_<token>}.
   */
  private static String resolveDataset(Client client, RdfArchitectSource source) {
    if (source.snapshot() == null) {
      List<String> datasets = client.listDatasets();
      if (!datasets.contains(source.dataset())) {
        throw new RdfArchitectException(
            "RDFArchitect has no dataset \""
                + source.dataset()
                + "\" in "
                + (client.borrowsSession() ? "the connected session" : "a fresh session")
                + (datasets.isEmpty()
                    ? " (it exposes none — datasets belong to a session, so import the schema in"
                        + " the connected window, or address a snapshot instead)"
                    : "; it exposes " + datasets));
      }
      return source.dataset();
    }
    client.loadSnapshot(source.snapshot());
    if (source.dataset() != null) {
      return source.dataset();
    }
    String suffix = "_" + source.snapshot();
    return client.listDatasets().stream()
        .filter(name -> name.endsWith(suffix))
        .findFirst()
        .orElseThrow(
            () ->
                new RdfArchitectException(
                    "RDFArchitect loaded snapshot "
                        + source.snapshot()
                        + " but exposes no dataset for it"));
  }

  /** Signals that an RDFArchitect instance could not be read. */
  public static final class RdfArchitectException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception describing why the instance could not be read.
     *
     * @param message what could not be read, and why
     */
    public RdfArchitectException(String message) {
      super(message);
    }

    /**
     * Creates an exception describing why the instance could not be read.
     *
     * @param message what could not be read, and why
     * @param cause the underlying transport or parse failure
     */
    public RdfArchitectException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The slice of RDFArchitect's REST API this needs. All calls share one session (the {@code
   * RDFA_SESSION_ID} cookie), which is what a loaded snapshot is attached to.
   */
  private static final class Client {
    private final String api;
    private final Duration timeout;
    private final HttpClient http;
    private final String sessionId;

    Client(String baseUrl, Duration timeout, String sessionId) {
      this.api = baseUrl + "/api";
      this.timeout = timeout;
      this.sessionId = sessionId;
      this.http =
          HttpClient.newBuilder()
              .connectTimeout(timeout)
              .cookieHandler(new CookieManager())
              .build();
    }

    boolean borrowsSession() {
      return sessionId != null;
    }

    /** The id of the newest change of a graph, or {@code ""} when it has none yet. */
    String latestChangeId(String dataset, String graph) {
      JsonNode changes =
          readJson(
              get(
                  "/datasets/" + encode(dataset) + "/graphs/" + encode(graph) + "/changes",
                  "application/json"));
      JsonNode newest = changes.isArray() && !changes.isEmpty() ? changes.get(0) : null;
      return newest == null ? "" : newest.path("changeId").asText("");
    }

    void loadSnapshot(String token) {
      get("/snapshots/" + encode(token), "text/plain");
    }

    List<String> listDatasets() {
      var names = new ArrayList<String>();
      readJson(get("/datasets", "application/json")).forEach(node -> names.add(node.asText()));
      return names;
    }

    /** Graph URIs of a dataset; the API serves them as {@code {prefix, suffix}} objects. */
    List<String> listGraphs(String dataset) {
      var uris = new ArrayList<String>();
      readJson(get("/datasets/" + encode(dataset) + "/graphs", "application/json"))
          .forEach(
              node ->
                  uris.add(
                      node.isTextual()
                          ? node.asText()
                          : node.path("prefix").asText("") + node.path("suffix").asText("")));
      return uris;
    }

    String fetchGraph(String dataset, String graph) {
      return get(
          "/datasets/" + encode(dataset) + "/graphs/" + encode(graph) + "/content", "text/turtle");
    }

    private JsonNode readJson(String body) {
      try {
        return JSON.readTree(body);
      } catch (IOException e) {
        throw new RdfArchitectException(
            "RDFArchitect returned malformed JSON: " + e.getMessage(), e);
      }
    }

    private String get(String path, String accept) {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(api + path))
              .timeout(timeout)
              .header("Accept", accept)
              .GET();
      if (sessionId != null) {
        // Borrowing the browser's session is what makes its live datasets readable.
        builder.header("Cookie", SESSION_COOKIE + "=" + sessionId);
      }
      HttpRequest request = builder.build();
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RdfArchitectException("Interrupted while reading " + api + path, e);
      } catch (IOException e) {
        throw new RdfArchitectException(
            "Could not reach RDFArchitect at " + api + path + ": " + e.getMessage(), e);
      }
      if (response.statusCode() >= 400) {
        throw new RdfArchitectException(
            "GET " + path + " → HTTP " + response.statusCode() + describe(response.body()));
      }
      return response.body();
    }

    private static String describe(String body) {
      String trimmed = body == null ? "" : body.strip();
      return trimmed.isEmpty() ? "" : " — " + trimmed.substring(0, Math.min(200, trimmed.length()));
    }

    private static String encode(String segment) {
      return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
  }
}
