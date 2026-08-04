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

  private RdfArchitectSchemaLoader() {}

  /**
   * Loads the schema addressed by {@code source}.
   *
   * @param timeout per-request timeout
   * @throws RdfArchitectException when the instance cannot be reached or does not hold the
   *     requested dataset
   */
  public static EndpointSchema load(RdfArchitectSource source, Duration timeout) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(timeout, "timeout");
    Client client = new Client(source.baseUrl(), timeout);

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
                + "\" in a fresh session"
                + (datasets.isEmpty()
                    ? " (it exposes none — datasets are session-scoped unless the instance is"
                        + " backed by a triple store; use a snapshot link instead)"
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

    Client(String baseUrl, Duration timeout) {
      this.api = baseUrl + "/api";
      this.timeout = timeout;
      this.http =
          HttpClient.newBuilder()
              .connectTimeout(timeout)
              .cookieHandler(new CookieManager())
              .build();
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
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(api + path))
              .timeout(timeout)
              .header("Accept", accept)
              .GET()
              .build();
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
