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

package de.soptim.opencgmes.sparql.validation.lsp;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.concurrent.Future;

/**
 * Entry point for the CIMcheck Language Server.
 *
 * <p>The server communicates over stdin/stdout using the LSP JSON-RPC protocol.
 * All logging goes to stderr so it never interferes with the protocol stream.</p>
 *
 * <p>Launch from the VS Code extension with:</p>
 * <pre>
 *   java -jar cimcheck-lsp.jar
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        SparqlLanguageServer server = new SparqlLanguageServer();
        var launcher = LSPLauncher.createServerLauncher(
                server, System.in, System.out);
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        Future<?> listening = launcher.startListening();
        listening.get(); // blocks until the client disconnects
    }
}
