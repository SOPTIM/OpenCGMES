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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.List;

/**
 * Decoding of {@code workspace/executeCommand} arguments shared by the notebook commands.
 *
 * <p>Over JSON-RPC, lsp4j delivers arguments as Gson {@link JsonElement}s; a direct in-process call
 * may pass a JSON {@link String} instead. Every notebook command takes a single JSON-object
 * argument, so the dual-case coercion lives here rather than in each handler.
 */
public final class CommandArguments {

  private static final Gson GSON = new Gson();

  private CommandArguments() {}

  /**
   * The command's first argument as JSON, or {@code null} when there is none (or it is JSON {@code
   * null}). Throws Gson's {@code JsonSyntaxException} when a String argument is not JSON.
   */
  public static JsonElement firstAsJson(List<Object> arguments) {
    if (arguments == null || arguments.isEmpty() || arguments.get(0) == null) {
      return null;
    }
    Object first = arguments.get(0);
    JsonElement el =
        first instanceof JsonElement je ? je : GSON.fromJson(first.toString(), JsonElement.class);
    return el == null || el.isJsonNull() ? null : el;
  }
}
