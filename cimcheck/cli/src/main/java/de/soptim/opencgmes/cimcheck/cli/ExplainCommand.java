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

package de.soptim.opencgmes.cimcheck.cli;

import de.soptim.opencgmes.cimcheck.cli.config.CliConfig;
import de.soptim.opencgmes.cimcheck.cli.config.ConfigLoader;
import de.soptim.opencgmes.cimcheck.cli.schema.SchemaLoader;
import de.soptim.opencgmes.cimcheck.core.DefaultPrefixes;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.explain.QueryExplanation;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The {@code cimcheck explain} subcommand: prints the static SPARQL algebra plan for one or more
 * query files, like Apache Jena's {@code arq.qparse --print=query,op,opt}. No data is executed.
 *
 * <p>A schema is optional. When one is available (via {@code --config}/{@code --schema} or an
 * auto-discovered {@code .cgmes/validation.json}) its detected {@code cim:} prefix is injected so
 * prefix-free queries parse; otherwise the built-in default prefixes are used. The algebra plan
 * itself does not depend on the schema.</p>
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — every input produced a plan</li>
 *   <li>1 — at least one input could not be explained (e.g. an Update or a parse error)</li>
 *   <li>2 — usage or I/O error</li>
 * </ul>
 */
@Command(
        name        = "explain",
        description = {
            "Print the static SPARQL algebra plan (compiled + optimized) for query file(s).",
            "Like 'arq.qparse --print=query,op,opt' — no data is executed.",
            "",
            "Exit codes: 0=all explained  1=some not explainable  2=usage/I-O error"
        },
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public class ExplainCommand implements Callable<Integer> {

    @Parameters(
            paramLabel  = "<file>",
            arity       = "1..*",
            description = "SPARQL query file(s) to explain. Use '-' to read from stdin."
    )
    private List<String> inputs;

    @Option(
            names       = {"-c", "--config"},
            paramLabel  = "<file>",
            description = "Config file (default: auto-discovers .cgmes/validation.json upward from CWD)."
    )
    private Path configFile;

    @Option(
            names       = {"-s", "--schema"},
            paramLabel  = "<file>",
            description = "Schema RDFS file(s). Repeatable. Alternative to --config."
    )
    private List<Path> schemaFiles = List.of();

    @Override
    public Integer call() {
        // Build a schema-aware API when a schema is reachable; otherwise fall back to static explain.
        SparqlValidationApi api = tryBuildApi();

        var writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        String stdinText = null;
        boolean allExplained = true;

        for (int i = 0; i < inputs.size(); i++) {
            String input  = inputs.get(i);
            String source = "-".equals(input) ? "<stdin>" : input;
            String text;
            try {
                if ("-".equals(input)) {
                    if (stdinText == null) stdinText = readStdin();
                    text = stdinText;
                } else {
                    text = Files.readString(Path.of(input), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                System.err.println("Error reading " + source + ": " + e.getMessage());
                return ExitCode.USAGE;
            }

            QueryExplanation explanation = api != null
                    ? api.explain(text)
                    : SparqlValidationApi.explainStatic(text);

            if (inputs.size() > 1) writer.println("===== " + source + " =====");
            writer.println(explanation.render());
            if (!explanation.hasPlan()) allExplained = false;
        }
        writer.flush();

        return allExplained ? ExitCode.OK : 1;
    }

    /**
     * Loads a schema index the same way {@link ValidateCommand} does and returns a configured API,
     * or {@code null} when no schema is configured / discoverable. Errors are reported to stderr but
     * are not fatal — explain degrades to the schema-independent path.
     */
    private SparqlValidationApi tryBuildApi() {
        try {
            RdfsSchemaIndex index;
            CliConfig config = null;
            if (!schemaFiles.isEmpty()) {
                index = SchemaLoader.load(schemaFiles);
            } else if (configFile != null) {
                config = ConfigLoader.load(configFile);
                index = SchemaLoader.load(config, configFile.toAbsolutePath().getParent());
            } else {
                var discovered = ConfigLoader.discover(Path.of("."));
                if (discovered.isEmpty()) return null; // no schema — use static explain
                config = discovered.get();
                index = SchemaLoader.load(config, Path.of(".").toAbsolutePath());
            }
            var prefixes = (config != null && config.prefixes() != null)
                    ? config.prefixes()
                    : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, index);
            var checkStdVocab = config == null || config.checkStandardVocabulary();
            return new SparqlValidationApi(index, prefixes, checkStdVocab);
        } catch (ConfigLoader.ConfigException | SchemaLoader.SchemaLoadException e) {
            System.err.println("Warning: could not load schema (" + e.getMessage()
                    + "); explaining with built-in prefixes only.");
            return null;
        }
    }

    private static String readStdin() throws IOException {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
