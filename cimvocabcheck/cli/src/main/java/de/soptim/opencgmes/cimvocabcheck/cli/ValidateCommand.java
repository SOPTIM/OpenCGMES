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

package de.soptim.opencgmes.cimvocabcheck.cli;

import de.soptim.opencgmes.cimvocabcheck.core.DefaultPrefixes;
import de.soptim.opencgmes.cimvocabcheck.core.SourceLocator;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationAnnotation;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationApi;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationCode;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationResult;
import de.soptim.opencgmes.cimvocabcheck.core.SparqlValidationSeverity;
import de.soptim.opencgmes.cimvocabcheck.core.StrictnessLevel;
import de.soptim.opencgmes.cimvocabcheck.core.VersionIri;
import de.soptim.opencgmes.cimvocabcheck.core.config.CimvocabcheckConfig;
import de.soptim.opencgmes.cimvocabcheck.core.config.ConfigLoader;
import de.soptim.opencgmes.cimvocabcheck.cli.output.CodeQualityFormatter;
import de.soptim.opencgmes.cimvocabcheck.cli.output.FileResult;
import de.soptim.opencgmes.cimvocabcheck.cli.output.Format;
import de.soptim.opencgmes.cimvocabcheck.cli.output.JsonFormatter;
import de.soptim.opencgmes.cimvocabcheck.cli.output.TextFormatter;
import de.soptim.opencgmes.cimvocabcheck.cli.schema.SchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.schema.EndpointSchema;
import de.soptim.opencgmes.cimvocabcheck.core.schema.EndpointSchemaLoader;
import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclValidationResult;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shared.PrefixMapping;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * The {@code cimvocabcheck} command: validates one or more SPARQL query files
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
        name        = "cimvocabcheck",
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
        sortOptions = false,
        subcommands = { ExplainCommand.class, InitCommand.class }
)
public class ValidateCommand implements Callable<Integer> {

    // ---- Inputs -----------------------------------------------------------------------------

    @Parameters(
            paramLabel = "<file>",
            arity      = "0..*",
            description = "SPARQL query file(s) to validate. Use '-' to read from stdin."
    )
    private List<String> inputs = List.of();

    // ---- Schema options ---------------------------------------------------------------------

    @Option(
            names       = {"-c", "--config"},
            paramLabel  = "<file>",
            description = "Config file (default: auto-discovers opencgmes.json upward from CWD)."
    )
    private Path configFile;

    @Option(
            names       = {"-s", "--schema"},
            paramLabel  = "<path>",
            description = "Schema RDFS file(s), or a directory of them (.rdf/.ttl/.owl). " +
                          "Repeatable. Alternative to --config."
    )
    private List<Path> schemaFiles = List.of();

    @Option(
            names       = {"-e", "--endpoint"},
            paramLabel  = "<url>",
            description = "SPARQL 1.1 endpoint hosting the CGMES schema and data. The schema is " +
                          "loaded from it and named graphs are auto-mapped to profiles. " +
                          "Alternative to --config / --schema."
    )
    private String endpoint;

    @Option(
            names       = {"--strict-endpoint"},
            description = "Fail (exit 2) when an --endpoint exposes no CIM schema graphs, " +
                          "instead of warning and falling back to a syntax-only check."
    )
    private boolean strictEndpoint;

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
            description = "Output format: text (default), json, or codequality (GitLab Code Quality).",
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
                          "Overrides the 'strictness' field in opencgmes.json. " +
                          "strict promotes WARN to ERROR; pedantic also promotes INFO to ERROR; " +
                          "permissive suppresses everything except unknown-term and syntax errors."
    )
    private String strictnessValue;

    // ---- Entry point ------------------------------------------------------------------------

    @Override
    public Integer call() {
        // With the 'explain' subcommand present, the positional <file> args are optional so that
        // 'cimvocabcheck explain ...' parses. When this (validate) command runs with no files, there is
        // nothing to do — show usage rather than silently succeeding.
        if (inputs.isEmpty()) {
            System.err.println("Error: no input file(s) given. Pass SPARQL file(s) to validate, "
                    + "or use the 'explain' subcommand. See --help.");
            return ExitCode.USAGE;
        }
        try {
            Format format = parseFormat();
            requireValidStrictnessFlag(); // fail fast on a bad flag before loading anything

            SchemaContext schema = resolveSchema();
            StrictnessLevel strictness = resolveStrictness(schema.config());
            Map<Node, Collection<VersionIri>> namedGraphScope = buildNamedGraphScope(schema);
            SparqlValidationApi api = buildApi(schema);

            List<FileResult> results = validateInputs(schema, api, namedGraphScope, strictness);
            writeResults(format, results);

            // Exit code: 1 if any file is invalid, otherwise 0.
            return results.stream().anyMatch(r -> !r.valid()) ? 1 : ExitCode.OK;
        } catch (AbortException e) {
            return e.code;
        }
    }

    // ---- Phases of call() -------------------------------------------------------------------

    /** Schema source resolved for a run: the index (null in syntax-only mode), the config it came
     *  from (if any), and any endpoint-derived named-graph scope. */
    private record SchemaContext(
            RdfsSchemaIndex index,
            CimvocabcheckConfig config,
            Map<Node, Collection<VersionIri>> endpointScope,
            boolean syntaxOnly) {

        static SchemaContext syntaxOnly(CimvocabcheckConfig config) {
            return new SchemaContext(null, config, null, true);
        }
    }

    private Format parseFormat() {
        try {
            return Format.parse(formatName);
        } catch (IllegalArgumentException e) {
            throw abortUsage(e.getMessage());
        }
    }

    /** Validates the {@code --strictness} flag early so config-load errors don't hide a bad flag. */
    private void requireValidStrictnessFlag() {
        if (strictnessValue == null) return;
        try {
            StrictnessLevel.parse(strictnessValue);
        } catch (IllegalArgumentException e) {
            throw abortUsage(e.getMessage());
        }
    }

    /**
     * Loads the schema. From a SPARQL endpoint (with auto graph→profile mapping) when
     * {@code --endpoint} is given, otherwise from explicit schema files or a config file. There is
     * no bundled default schema, so an absent/empty source switches to syntax-only mode.
     */
    private SchemaContext resolveSchema() {
        return endpoint != null ? resolveSchemaFromEndpoint() : resolveSchemaFromFilesOrConfig();
    }

    private SchemaContext resolveSchemaFromEndpoint() {
        if (!schemaFiles.isEmpty() || configFile != null) {
            throw abortUsage("--endpoint cannot be combined with --schema or --config.");
        }
        EndpointSchema es;
        try {
            es = EndpointSchemaLoader.loadFromEndpoint(endpoint, Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            throw abortUsage("failed to load schema from endpoint " + endpoint + " — " + e.getMessage());
        }
        if (!es.hasSchema()) {
            String msg = "endpoint " + endpoint + " exposes no CIM schema graphs";
            if (strictEndpoint) {
                throw abortUsage(msg + " (--strict-endpoint).");
            }
            System.err.println("Warning: " + msg + " — validating SPARQL syntax only.");
            return SchemaContext.syntaxOnly(null);
        }
        System.err.println("Info: endpoint schema loaded — " + es.instanceGraphsMapped()
                + " instance graph(s) auto-mapped to profiles, "
                + es.schemaGraphNames().size() + " schema graph(s) detected.");
        if (!es.unmatchedGraphs().isEmpty()) {
            System.err.println("Warning: could not auto-detect a CGMES profile for "
                    + es.unmatchedGraphs().size()
                    + " named graph(s); their terms will be reported as unknown.");
        }
        return new SchemaContext(es.index(), null, es.namedGraphScope(), false);
    }

    private SchemaContext resolveSchemaFromFilesOrConfig() {
        try {
            if (!schemaFiles.isEmpty()) {
                return new SchemaContext(SchemaLoader.load(schemaFiles), null, null, false);
            }
            // Explicit --config, else an auto-discovered opencgmes.json. There is no bundled default
            // schema: a config without schemas (or no config at all) means syntax-only.
            CimvocabcheckConfig config;
            Path base;
            if (configFile != null) {
                config = ConfigLoader.load(configFile);
                base = configFile.toAbsolutePath().getParent();
            } else {
                config = ConfigLoader.discover(Path.of(".")).orElse(null);
                base = Path.of(".").toAbsolutePath();
            }
            RdfsSchemaIndex index = (config == null) ? null : SchemaLoader.load(config, base).orElse(null);
            if (index == null) {
                System.err.println("Info: no schema configured — checking syntax only. Use "
                        + "--schema/--config (or --endpoint) for schema-based validation.");
                return SchemaContext.syntaxOnly(config);
            }
            return new SchemaContext(index, config, null, false);
        } catch (ConfigLoader.ConfigException | SchemaLoader.SchemaLoadException e) {
            throw abortUsage(e.getMessage());
        }
    }

    /** Resolves the effective strictness: CLI flag → config file → {@code "default"}. */
    private StrictnessLevel resolveStrictness(CimvocabcheckConfig config) {
        String levelStr = strictnessValue != null ? strictnessValue
                : (config != null && config.strictness() != null ? config.strictness() : "default");
        try {
            return StrictnessLevel.parse(levelStr);
        } catch (IllegalArgumentException e) {
            throw abortUsage(e.getMessage());
        }
    }

    /**
     * Builds the named-graph scope: auto-detected from the endpoint, or from the config's
     * {@code namedGraphs}. In syntax-only mode there is no index, so the scope is empty.
     */
    private Map<Node, Collection<VersionIri>> buildNamedGraphScope(SchemaContext schema) {
        if (schema.endpointScope() != null) return schema.endpointScope();
        if (schema.index() == null) return Map.of();
        return SparqlValidationApi.buildNamedGraphScope(
                schema.config() == null ? Map.of() : schema.config().namedGraphs(),
                schema.index(),
                msg -> System.err.println("Warning: " + msg));
    }

    /** Builds the validation API, or {@code null} in syntax-only mode (no schema source). */
    private SparqlValidationApi buildApi(SchemaContext schema) {
        if (schema.syntaxOnly()) return null;
        CimvocabcheckConfig config = schema.config();
        var prefixes = (config != null && config.prefixes() != null)
                ? config.prefixes()
                : DefaultPrefixes.withDetectedCimPrefix(DefaultPrefixes.BUILT_IN, schema.index());
        boolean checkStdVocab = config == null || config.checkStandardVocabulary();
        return new SparqlValidationApi(schema.index(), prefixes, checkStdVocab);
    }

    private List<FileResult> validateInputs(
            SchemaContext schema,
            SparqlValidationApi api,
            Map<Node, Collection<VersionIri>> namedGraphScope,
            StrictnessLevel strictness) {

        var results = new ArrayList<FileResult>();
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
                throw new AbortException(ExitCode.USAGE);
            }
            results.add(validateOne(schema, api, namedGraphScope, strictness, input, source, text));
        }
        return results;
    }

    private FileResult validateOne(
            SchemaContext schema,
            SparqlValidationApi api,
            Map<Node, Collection<VersionIri>> namedGraphScope,
            StrictnessLevel strictness,
            String input, String source, String text) {

        if (schema.syntaxOnly()) {
            return applyStrictness(validateSyntaxOnly(source, text, isTurtleFile(input)), strictness);
        }
        if (isTurtleFile(input)) {
            return applyStrictness(validateShaclInput(api, source, text), strictness);
        }
        SparqlValidationResult r = validateSparql(api, text, namedGraphScope);
        List<SparqlValidationAnnotation> effective = strictness.apply(r.annotations());
        boolean valid = effective.stream()
                .noneMatch(a -> a.severity() == SparqlValidationSeverity.ERROR);
        return new FileResult(source, valid, effective);
    }

    private void writeResults(Format format, List<FileResult> results) {
        var writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        switch (format) {
            case TEXT -> new TextFormatter(writer, verbose).write(results);
            case JSON -> new JsonFormatter(writer, verbose).write(results);
            case CODEQUALITY -> new CodeQualityFormatter(writer, verbose).write(results);
        }
        writer.flush();

        // Machine-readable reports (json/codequality) are usually redirected to a file, leaving the
        // CI job log empty — print a one-line human summary to stderr so the log still shows the
        // outcome.
        if (format != Format.TEXT) {
            long errors = results.stream().mapToLong(FileResult::errorCount).sum();
            long warnings = results.stream().mapToLong(FileResult::warnCount).sum();
            long invalid = results.stream().filter(r -> !r.valid()).count();
            System.err.printf(
                    "cimvocabcheck: %d file(s), %d invalid, %d error(s), %d warning(s)%n",
                    results.size(), invalid, errors, warnings);
        }
    }

    /** Prints {@code "Error: <message>"} and signals a usage-error early exit. */
    private static AbortException abortUsage(String message) {
        System.err.println("Error: " + message);
        return new AbortException(ExitCode.USAGE);
    }

    /** Unwinds {@link #call()} with a specific process exit code; the message is already printed. */
    private static final class AbortException extends RuntimeException {
        final int code;
        AbortException(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private SparqlValidationResult validateSparql(
            SparqlValidationApi api,
            String queryText,
            Map<Node, Collection<VersionIri>> namedGraphScope) {

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

    /**
     * Schema-independent syntax check, used when an {@code --endpoint} exposes no schema and
     * {@code --strict-endpoint} was not set: SPARQL files get a syntax check, Turtle/SHACL files
     * get a Turtle parse plus an embedded-SPARQL syntax check, so broken input still surfaces.
     */
    private static FileResult validateSyntaxOnly(String source, String text, boolean turtle) {
        if (!turtle) {
            SparqlValidationResult r = SparqlValidationApi.checkSyntaxOnly(text);
            boolean valid = r.annotations().stream()
                    .noneMatch(a -> a.severity() == SparqlValidationSeverity.ERROR);
            return new FileResult(source, valid, r.annotations());
        }
        Graph graph;
        try {
            graph = parseTurtleGraph(text);
        } catch (RuntimeException e) {
            return new FileResult(source, false, List.of(turtleParseError(e)));
        }
        ShaclValidationResult r = SparqlValidationApi.checkShaclSyntaxOnly(graph);
        return new FileResult(
                source, r.isValid(), flattenShaclAnnotations(r, text, graph.getPrefixMapping()));
    }

    private FileResult validateShaclInput(SparqlValidationApi api, String source, String text) {
        Graph graph;
        try {
            graph = parseTurtleGraph(text);
        } catch (RuntimeException e) {
            return new FileResult(source, false, List.of(turtleParseError(e)));
        }

        ShaclValidationResult r;
        if (!profiles.isEmpty()) {
            var versionIris = profiles.stream().map(VersionIri::of).collect(Collectors.toList());
            r = api.validateShacl(graph, versionIris);
        } else {
            r = api.validateShacl(graph);
        }

        return new FileResult(
                source, r.isValid(), flattenShaclAnnotations(r, text, graph.getPrefixMapping()));
    }

    private static Graph parseTurtleGraph(String text) {
        var model = ModelFactory.createDefaultModel();
        RDFParser.fromString(text, Lang.TURTLE).parse(model);
        return model.getGraph();
    }

    private static SparqlValidationAnnotation turtleParseError(Exception e) {
        return new SparqlValidationAnnotation(
                SparqlValidationSeverity.ERROR,
                null,
                null,
                "Turtle/SHACL parse error: " + e.getMessage(),
                SparqlValidationCode.SYNTAX_ERROR,
                null,
                List.of(),
                List.of(),
                null);
    }

    /**
     * Flattens shape-structure and embedded-SPARQL annotations into a single list.
     *
     * <p>Shape-structure annotations come out of {@link
     * de.soptim.opencgmes.cimvocabcheck.core.shacl.ShaclShapeAnalyzer} with {@code null} line/column
     * (the analyzer sees only a Graph, not the source text). This method fills them in via {@link
     * SourceLocator} using the original Turtle source and its prefix mapping.
     *
     * <p>Embedded-SPARQL positions are relative to the embedded query string, not the Turtle file —
     * they are stripped and the message prefixed so the output is unambiguous.
     */
    private static List<SparqlValidationAnnotation> flattenShaclAnnotations(
            ShaclValidationResult r, String turtleSource, PrefixMapping prefixes) {
        var annotations = new ArrayList<SparqlValidationAnnotation>();
        for (var a : r.shapeAnnotations()) {
            annotations.add(a.line() != null ? a : withLocation(a, turtleSource, prefixes));
        }
        for (var er : r.embeddedResults()) {
            String kind = er.embedded().kind().toString();
            for (var a : er.result().annotations()) {
                annotations.add(new SparqlValidationAnnotation(
                        a.severity(),
                        null,
                        null,
                        "[embedded " + kind + "] " + a.message(),
                        a.code(),
                        a.term(),
                        a.selectedProfiles(),
                        a.foundInOtherProfiles(),
                        a.graph()));
            }
        }
        return List.copyOf(annotations);
    }

    private static SparqlValidationAnnotation withLocation(
            SparqlValidationAnnotation a, String source, PrefixMapping prefixes) {
        if (a.term() == null) {
            return a;
        }
        var loc = SourceLocator.locate(source, a.term(), prefixes);
        if (loc.line() == null) {
            return a;
        }
        return new SparqlValidationAnnotation(
                a.severity(),
                loc.line(),
                loc.column(),
                a.message(),
                a.code(),
                a.term(),
                a.selectedProfiles(),
                a.foundInOtherProfiles(),
                a.graph());
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
