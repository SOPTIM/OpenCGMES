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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-notebook default endpoint: the {@code # [endpoint=...]} value a notebook's directive-less
 * cells run — and validate — against.
 *
 * <p>The default is a client-side choice (VS Code's <em>Set Cell Endpoint → Notebook default</em>,
 * remembered in workspace state rather than written into the notebook file), so the server can only
 * learn about it by being told: the client pushes every change, and re-pushes on startup, through
 * the {@value SparqlWorkspaceService#CMD_SET_DEFAULT_ENDPOINT} command. Without that, a cell with
 * no directive of its own has no schema source and falls back to syntax-only validation.
 *
 * <p>Entries are keyed by the notebook's own path, which a cell URI carries too — see {@link
 * DocumentUris} — so a cell can look up the notebook it belongs to.
 */
final class NotebookDefaults {

  private static final Logger LOG = LoggerFactory.getLogger(NotebookDefaults.class);
  private static final Gson GSON = new Gson();

  private final Map<String, String> endpoints = new ConcurrentHashMap<>();
  private final List<Runnable> onChangeCallbacks = new CopyOnWriteArrayList<>();

  /** Registers a callback invoked after each change (typically: revalidate open documents). */
  void addOnChangeCallback(Runnable callback) {
    onChangeCallbacks.add(callback);
  }

  /**
   * Applies one {@value SparqlWorkspaceService#CMD_SET_DEFAULT_ENDPOINT} invocation. Its single
   * argument is {@code {"notebookUri": "...", "endpoint": "..."}}; a {@code null}/absent endpoint
   * clears the notebook's default. Over JSON-RPC lsp4j delivers arguments as Gson {@link
   * JsonElement}s, while an in-process call may pass a JSON {@link String} — both are accepted, as
   * in {@code NotebookCommandHandler}.
   */
  void apply(List<Object> arguments) {
    if (arguments == null || arguments.isEmpty() || arguments.get(0) == null) {
      LOG.warn("Ignoring setDefaultEndpoint without an argument");
      return;
    }
    try {
      Object first = arguments.get(0);
      JsonElement el =
          first instanceof JsonElement je ? je : GSON.fromJson(first.toString(), JsonElement.class);
      if (el == null || !el.isJsonObject()) {
        LOG.warn("Ignoring malformed setDefaultEndpoint argument: {}", first);
        return;
      }
      JsonObject obj = el.getAsJsonObject();
      set(stringField(obj, "notebookUri"), stringField(obj, "endpoint"));
    } catch (RuntimeException e) {
      LOG.warn("Ignoring malformed setDefaultEndpoint argument: {}", e.getMessage());
    }
  }

  /** Sets a notebook's default endpoint, or clears it when {@code endpoint} is null/blank. */
  void set(String notebookUri, String endpoint) {
    String key = key(notebookUri);
    if (key == null) {
      LOG.debug("Ignoring notebook default for unusable URI {}", notebookUri);
      return;
    }
    if (endpoint == null || endpoint.isBlank()) {
      endpoints.remove(key);
    } else {
      endpoints.put(key, endpoint.trim());
    }
    LOG.debug("Notebook default for {} is now {}", key, endpoints.get(key));
    fireOnChange();
  }

  /**
   * The default endpoint of the notebook owning {@code cellUri}, or {@code null} if none is set.
   */
  String forCell(String cellUri) {
    String key = key(cellUri);
    return key == null ? null : endpoints.get(key);
  }

  /**
   * The map key a notebook URI and its cell URIs agree on: the notebook's path. Unsaved notebooks
   * have none, so their opaque URI body ({@code untitled:Untitled-1} and {@code
   * vscode-notebook-cell:Untitled-1#…} alike) is used instead.
   */
  private static String key(String uri) {
    Path path = DocumentUris.path(uri);
    if (path != null) {
      return path.normalize().toString();
    }
    if (uri == null || uri.isBlank()) {
      return null;
    }
    try {
      String body = URI.create(uri.split("#", 2)[0]).getSchemeSpecificPart();
      return body == null || body.isBlank() ? null : body;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String stringField(JsonObject obj, String name) {
    JsonElement value = obj.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  private void fireOnChange() {
    for (Runnable callback : onChangeCallbacks) {
      try {
        callback.run();
      } catch (Exception e) {
        LOG.error("Notebook-default change callback failed: {}", e.getMessage(), e);
      }
    }
  }
}
