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

import de.soptim.opencgmes.cimcheck.core.DefaultPrefixes;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationResult;
import de.soptim.opencgmes.cimcheck.core.SparqlValidationSeverity;
import de.soptim.opencgmes.cimcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimcheck.core.VersionIri;
import de.soptim.opencgmes.cimcheck.cli.config.CliConfig;
import de.soptim.opencgmes.cimcheck.cli.config.ConfigLoader;
import de.soptim.opencgmes.cimcheck.cli.output.FileResult;
import de.soptim.opencgmes.cimcheck.cli.output.Format;
import de.soptim.opencgmes.cimcheck.cli.output.JsonFormatter;
import de.soptim.opencgmes.cimcheck.cli.output.TextFormatter;
import de.soptim.opencgmes.cimcheck.cli.schema.SchemaLoader;
import de.soptim.opencgmes.cimcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * The {@code cimcheck} command: validates one or more SPARQL query files
 * against CIM/CGMES schema profiles.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — all queries are valid (no ERROR annotations)</li>
 *   <li>1 — at least one query has ERROR annotations</li>
 *   <li>2 — usage or configuration error</li>
 * </ul>
 */
@Command(
        name        = "cimcheck",
        description = {
            "Validate SPARQL queries and SHACL shapes against CIM/CGMES schema profiles.",
            "",
            "Schema is loaded from a config file (auto-discovered or --config),",
            "or from explicit schema files passed with --schema.",
            "",
            "Exit codes: 0=valid  1=has errors  2=usage/config error"
        },
        mixinStandardHelpOptions = true,
        version     = "1.0.0",
        sortOptions = false
)
public class ValidateCommand implements Callable<Integer> {

    // ---- Inputs -----------------------------------------------------------------------------

    @Parameters(
            paramLabel = "<file>",
            arity      = "1..*",
            description = "SPARQL query file(s) to validate. Use '-' to read from stdin."
    )
    private List<String> inputs;

    // ---- Schema options ---------------------------------------------------------------------

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

    // ---- Scope options -----------------------------------------------------------------------

    @Option(
            names       = {"-p", "--profile"},
            paramLabel  = "<iri>",
            description = "Restrict to this profile IRI. Repeatable. " +
                          "Ignored when the config file contains namedGraphs."
    )
    private List<String> profiles = List.of();

    // ---- Output options ---------------------------------------------------------------------

    @Option(
            names       = {"-f", "--format"},
            paramLabel  = "<format>",
            description = "Output format: text (default) or json.",
            defaultValue = "text"
    )
    private String formatName;

    @Option(
            names       = {"-v", "--verbose"},
            description = "Also report WARN and INFO annotations (default: ERROR only)."
    )
    private boolean verbose;

    @Option(
            names       = {"--strictness"},
            paramLabel  = "<level>",
            description = "Validation strictness: permissive, default, strict, or pedantic. " +
                          "Overrides the 'strictness' field in .cgmes/validation.json. " +
                          "strict promotes WARN to ERROR; pedantic also promotes INFO to ERROR; " +
                          "permissive suppresses everything except unknown-term and syntax errors."
    )
    private String strictnessValue;

    // ---- Entry point ------------------------------------------------------------------------

    @Override
    public Integer call() {
        Format format;
        try {
            format = Format.parse(formatName);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return ExitCode.USAGE;
        }

        // Resolve strictness early so config-load errors don't hide a bad flag.
        if (strictnessValue != null) {
            try {
                StrictnessLevel.parse(strictnessValue);
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return ExitCode.USAGE;
            }
        }

        // 1. Load schema index.
        RdfsSchemaIndex index;
        CliConfig config = null;
        try {
            if (!schemaFiles.isEmpty()) {
                index = SchemaLoader.load(schemaFiles);
            } else if (configFile != null) {
                config = ConfigLoader.load(configFile);
                Path base = configFile.toAbsolutePath().getParent();
                index = SchemaLoader.load(config, base);
            } else {
                // Auto-discover config.
                var discovered = ConfigLoader.discover(Path.of("."));
                if (discovered.isEmpty()) {
                    System.err.println("Error: No .cgmes/validation.json found and no --schema or --config given.");
                    System.err.println("       Create .cgmes/validation.json or pass schema files with --schema.");
                    return ExitCode.USAGE;
                }
                config = discovered.get();
                Path base = Path.of(".").toAbsolutePath();
                index = SchemaLoader.load(config, base);
            }
        } catch (ConfigLoader.ConfigException | SchemaLoader.SchemaLoadException e) {
            System.err.println("Error: " + e.getMessage());
            return ExitCode.USAGE;
        }

        // 2. Resolve effective strictness: CLI flag → config file → "default".
        String levelStr = strictnessValue != null ? strictnessValue
                : (config != null && config.strictness() != null ? config.strictness() : "default");
        StrictnessLevel strictness;
        try {
            strictness = StrictnessLevel.parse(levelStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return ExitCode.USAGE;
        }

        // 3. Build scope: named-graph map or profile list.
        Map<Node, Collection<VersionIri>> namedGraphScope = SparqlValidationApi.buildNamedGraphScope(
                config == null ? Map.of() : config.namedGraphs(),
                index,
                msg -> System.err.println("Warning: " + msg));

        // 4. Validate each input.
        var effectivePrefixes = (config != null && config.prefixes() != null)
                ? config.prefixes()
                : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, index);
        var api       = new SparqlValidationApi(index, effectivePrefixes);
        var results   = new ArrayList<FileResult>();
        String stdinText = null;
        for (String input : inputs) {
            String source = input.equals("-") ? "<stdin>" : input;
            String text;
            try {
                if ("-".equals(input)) {
                    if (stdinText == null) stdinText = readStdin();
                    text = stdinText;
                } else {
                    text = readInput(input);
                }
            } catch (IOException e) {
                System.err.println("Error reading " + source + ": " + e.getMessage());
                return ExitCode.USAGE;
            }

            FileResult fileResult;
            if (isTurtleFile(input)) {
                fileResult = applyStrictness(validateShaclInput(api, source, text), strictness);
            } else {
                SparqlValidationResult r = validateSparql(api, text, namedGraphScope, index);
                List<SparqlValidationAnnotation> effective = strictness.apply(r.annotations());
                boolean valid = effective.stream()
                        .noneMatch(a -> a.severity() == SparqlValidationSeverity.ERROR);
                fileResult = new FileResult(source, valid, effective);
            }
            results.add(fileResult);
        }

        // 5. Format output.
        var writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        switch (format) {
            case TEXT -> new TextFormatter(writer, verbose).write(results);
            case JSON -> new JsonFormatter(writer, verbose).write(results);
        }
        writer.flush();

        // 6. Exit code: 1 if any file is invalid.
        boolean anyInvalid = results.stream().anyMatch(r -> !r.valid());
        return anyInvalid ? 1 : ExitCode.OK;
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private SparqlValidationResult validateSparql(
            SparqlValidationApi api,
            String queryText,
            Map<Node, Collection<VersionIri>> namedGraphScope,
            RdfsSchemaIndex index) {

        if (!namedGraphScope.isEmpty()) {
            return api.validateSparql(queryText, namedGraphScope);
        }
        if (!profiles.isEmpty()) {
            var versionIris = profiles.stream()
                    .map(VersionIri::of)
                    .collect(Collectors.toList());
            return api.validateSparql(queryText, versionIris);
        }
        return api.validateSparql(queryText);
    }

    private FileResult validateShaclInput(SparqlValidationApi api, String source, String text) {
        Graph graph;
        try {
            var model = ModelFactory.createDefaultModel();
            RDFParser.fromString(text, Lang.TURTLE).parse(model);
            graph = model.getGraph();
        } catch (Exception e) {
            var parseError = new SparqlValidationAnnotation(
                    SparqlValidationSeverity.ERROR, null, null,
                    "Turtle/SHACL parse error: " + e.getMessage(),
                    SparqlValidationCode.SYNTAX_ERROR,
                    null, List.of(), List.of(), null);
            return new FileResult(source, false, List.of(parseError));
        }

        ShaclValidationResult r;
        if (!profiles.isEmpty()) {
            var versionIris = profiles.stream().map(VersionIri::of).collect(Collectors.toList());
            r = api.validateShacl(graph, versionIris);
        } else {
            r = api.validateShacl(graph);
        }

        // Flatten shape-structure and embedded-SPARQL annotations into a single list.
        // Embedded-SPARQL positions are relative to the query string, not the Turtle file —
        // strip them and prefix the message so the output is unambiguous.
        var annotations = new ArrayList<SparqlValidationAnnotation>(r.shapeAnnotations());
        for (var er : r.embeddedResults()) {
            String kind = er.embedded().kind().toString();
            for (var a : er.result().annotations()) {
                annotations.add(new SparqlValidationAnnotation(
                        a.severity(), null, null,
                        "[embedded " + kind + "] " + a.message(),
                        a.code(), a.term(), a.selectedProfiles(), a.foundInOtherProfiles(), a.graph()));
            }
        }
        return new FileResult(source, r.isValid(), List.copyOf(annotations));
    }

    private static boolean isTurtleFile(String input) {
        if ("-".equals(input)) return false;
        String lower = input.toLowerCase();
        return lower.endsWith(".ttl") || lower.endsWith(".shacl");
    }

    private static String readStdin() throws IOException {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readInput(String input) throws IOException {
        return Files.readString(Path.of(input), StandardCharsets.UTF_8);
    }

    private static FileResult applyStrictness(FileResult r, StrictnessLevel level) {
        List<SparqlValidationAnnotation> effective = level.apply(r.annotations());
        boolean valid = effective.stream()
                .noneMatch(a -> a.severity() == SparqlValidationSeverity.ERROR);
        return new FileResult(r.source(), valid, effective);
    }
}
