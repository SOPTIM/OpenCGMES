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

/**
 * Credentials for an HTTP target, attached by the client per execute request. This is the
 * <b>only</b> place credentials ever travel: the client keeps them in VS Code SecretStorage (never
 * in {@code opencgmes.jsonc}), and the server uses them solely to build the request's {@code
 * Authorization} header — they are never logged, cached, or echoed back in responses.
 *
 * @param type the scheme; only {@code "basic"} is understood.
 * @param username the user name.
 * @param password the password; may be empty.
 */
record ExecAuth(String type, String username, String password) {

  /** The only {@link #type()} understood. */
  static final String TYPE_BASIC = "basic";

  /** Redacts credentials wherever a record accidentally ends up in a log or error message. */
  @Override
  public String toString() {
    return "ExecAuth[type=" + type + ", username=***, password=***]";
  }
}
