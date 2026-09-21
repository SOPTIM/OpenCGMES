//    Copyright (c) 2026 SOPTIM AG
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
//    SPDX-License-Identifier: Apache-2.0

using System.Diagnostics;
using System.Text.Json;

namespace Soptim.CimVocabCheck;

/// <summary>
/// Runs the engine and turns its report into typed .NET.
/// </summary>
/// <remarks>
/// Validation is a <em>batch</em> operation here, and deliberately so: loading a CGMES profile
/// set costs around a second, while validating one more query after that costs almost nothing.
/// Passing every input to one call is the difference between a CI job that takes a second and
/// one that takes a second per file.
/// </remarks>
public static class Validator
{
    /// <summary>Exit code the CLI uses for "every input is clean".</summary>
    public const int ExitClean = 0;

    /// <summary>Exit code the CLI uses for "at least one input has an error".</summary>
    public const int ExitFindings = 1;

    /// <summary>Exit code the CLI uses for a usage or configuration failure.</summary>
    public const int ExitUsage = 2;

    /// <summary>The name the engine reports an input under when it was read from stdin.</summary>
    public const string StandardInputName = "<stdin>";

    /// <summary>Suffixes the engine reads as Turtle (SHACL shapes); anything else is SPARQL.</summary>
    public static readonly IReadOnlyList<string> TurtleSuffixes = [".ttl", ".shacl"];

    /// <summary>
    /// Validates SPARQL queries and SHACL shapes, and returns one report for all of them.
    /// </summary>
    /// <remarks>
    /// Files ending in <c>.ttl</c>/<c>.shacl</c> are read as SHACL shapes, everything else as
    /// SPARQL. Pass paths relative to <see cref="ValidationOptions.WorkingDirectory"/>: they
    /// appear verbatim in the report, and CI annotations only line up when they are
    /// repository-relative. Findings are data, not exceptions — a query with errors comes back
    /// as a report whose <see cref="ReportExtensions.IsOk"/> is <c>false</c>.
    /// </remarks>
    /// <param name="paths">The inputs to validate.</param>
    /// <param name="options">How to run; defaults to a discovered engine and every finding.</param>
    /// <param name="cancellationToken">Cancels the run.</param>
    /// <returns>One report covering every input.</returns>
    public static async Task<Report> ValidateAsync(
        IEnumerable<string> paths,
        ValidationOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(paths);
        return await RunAsync([.. paths], null, options ?? new ValidationOptions(), cancellationToken)
            .ConfigureAwait(false);
    }

    /// <summary>
    /// Validates SPARQL queries and SHACL shapes. The blocking form of <see cref="ValidateAsync"/>.
    /// </summary>
    /// <param name="paths">The inputs to validate.</param>
    /// <param name="options">How to run; defaults to a discovered engine and every finding.</param>
    /// <returns>One report covering every input.</returns>
    public static Report Validate(IEnumerable<string> paths, ValidationOptions? options = null) =>
        Block(() => ValidateAsync(paths, options));

    /// <summary>
    /// Validates a SPARQL query held in memory, by feeding it to the engine on stdin.
    /// </summary>
    /// <remarks>
    /// The engine tells SHACL from SPARQL by file suffix and stdin has none, so shapes must come
    /// from a file. The single result is reported under <see cref="StandardInputName"/>.
    /// </remarks>
    /// <param name="text">The query.</param>
    /// <param name="options">How to run.</param>
    /// <param name="cancellationToken">Cancels the run.</param>
    /// <returns>A report with one result.</returns>
    public static async Task<Report> ValidateTextAsync(
        string text,
        ValidationOptions? options = null,
        CancellationToken cancellationToken = default) =>
        await RunAsync(["-"], text, options ?? new ValidationOptions(), cancellationToken)
            .ConfigureAwait(false);

    /// <summary>
    /// Validates a query held in memory. The blocking form of <see cref="ValidateTextAsync"/>.
    /// </summary>
    /// <param name="text">The query.</param>
    /// <param name="options">How to run.</param>
    /// <returns>A report with one result.</returns>
    public static Report ValidateText(string text, ValidationOptions? options = null) =>
        Block(() => ValidateTextAsync(text, options));

    /// <summary>
    /// Returns the engine's own version string, for example <c>"1.4.2"</c>.
    /// </summary>
    /// <remarks>
    /// <c>"unknown"</c> means the engine runs from a checkout rather than a packaged artifact —
    /// the version is stamped into the JAR manifest at package time.
    /// </remarks>
    /// <param name="options">How to run.</param>
    /// <param name="cancellationToken">Cancels the run.</param>
    /// <returns>The engine's version.</returns>
    public static async Task<string> ToolVersionAsync(
        ValidationOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        options ??= new ValidationOptions();
        var engine = options.ResolveEngine();
        string[] arguments = ["--version"];
        var result = await SpawnAsync(engine, arguments, null, options, cancellationToken)
            .ConfigureAwait(false);

        if (result.ExitCode != ExitClean)
        {
            throw new ToolException(
                "the engine failed to report its version.",
                engine.BuildCommand(arguments, false, options.WorkingDirectory),
                result.ExitCode,
                result.StandardOutput,
                result.StandardError);
        }

        var first = result.StandardOutput.Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault(string.Empty).Trim();
        var space = first.IndexOf(' ', StringComparison.Ordinal);
        return space < 0 ? first : first[(space + 1)..].Trim();
    }

    /// <summary>
    /// Assembles the engine's argument list. Exposed so a caller can see what will be run.
    /// </summary>
    /// <param name="inputs">The inputs, already resolved for the engine.</param>
    /// <param name="options">The options to render.</param>
    /// <returns>The arguments, in order.</returns>
    public static IReadOnlyList<string> BuildArguments(
        IReadOnlyList<string> inputs,
        ValidationOptions options)
    {
        ArgumentNullException.ThrowIfNull(inputs);
        ArgumentNullException.ThrowIfNull(options);

        var arguments = new List<string> { "--format", "json" };
        if (options.Verbose)
        {
            arguments.Add("--verbose");
        }

        if (options.Config is { Length: > 0 } config)
        {
            arguments.Add("--config");
            arguments.Add(config);
        }

        foreach (var schema in options.Schema)
        {
            arguments.Add("--schema");
            arguments.Add(schema);
        }

        if (options.Endpoint is { Length: > 0 } endpoint)
        {
            arguments.Add("--endpoint");
            arguments.Add(endpoint);
        }

        if (options.StrictEndpoint)
        {
            arguments.Add("--strict-endpoint");
        }

        foreach (var profile in options.Profiles)
        {
            arguments.Add("--profile");
            arguments.Add(profile);
        }

        if (options.Strictness is { Length: > 0 } strictness)
        {
            arguments.Add("--strictness");
            arguments.Add(strictness);
        }

        arguments.AddRange(options.ExtraArguments);

        // Everything after "--" is an input, so a file whose name begins with "-" is not read as
        // a flag.
        arguments.Add("--");
        arguments.AddRange(inputs);
        return arguments;
    }

    /// <summary>
    /// Parses a <c>--format json</c> document, rejecting one from an incompatible contract major.
    /// </summary>
    /// <param name="standardOutput">What the engine wrote to stdout.</param>
    /// <returns>The report.</returns>
    public static Report ParseReport(string standardOutput) =>
        ParseReport(standardOutput, [], null, string.Empty);

    private static Report ParseReport(
        string standardOutput,
        IReadOnlyList<string> arguments,
        int? exitCode,
        string standardError)
    {
        ReportParseException Failure(string message) =>
            new(message, arguments, exitCode, standardOutput, standardError);

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(standardOutput);
        }
        catch (JsonException error)
        {
            throw Failure($"the engine's output was not JSON ({error.Message}).");
        }

        using (document)
        {
            var declared = document.RootElement.ValueKind == JsonValueKind.Object
                && document.RootElement.TryGetProperty("contractVersion", out var version)
                && version.ValueKind == JsonValueKind.String
                    ? version.GetString()
                    : null;

            var major = declared?.Split('.')[0];
            if (major != Contract.Major.ToString(System.Globalization.CultureInfo.InvariantCulture))
            {
                throw Failure(
                    $"this binding speaks report contract {Contract.Major}.x but the engine "
                    + $"produced '{declared}'. A contract major renames or removes fields, so "
                    + "upgrade the binding to match the engine.");
            }
        }

        try
        {
            return JsonSerializer.Deserialize(standardOutput, ReportJsonContext.Default.Report)
                ?? throw Failure("the engine's output was an empty report.");
        }
        catch (JsonException error)
        {
            throw Failure($"the engine's report did not match the contract ({error.Message}).");
        }
    }

    private static async Task<Report> RunAsync(
        IReadOnlyList<string> inputs,
        string? standardInput,
        ValidationOptions options,
        CancellationToken cancellationToken)
    {
        var engine = options.ResolveEngine();
        var directory = options.WorkingDirectory;

        var resolvedInputs = inputs.Select(input => engine.ResolvePath(input, directory)).ToList();
        var resolved = options with
        {
            Schema = [.. options.Schema.Select(path => engine.ResolvePath(path, directory))],
            Config = options.Config is null ? null : engine.ResolvePath(options.Config, directory),
        };

        var arguments = BuildArguments(resolvedInputs, resolved);
        var command = engine.BuildCommand(arguments, standardInput is not null, directory);
        var result = await SpawnAsync(engine, arguments, standardInput, options, cancellationToken)
            .ConfigureAwait(false);

        if (result.ExitCode == ExitUsage)
        {
            throw new ToolException(
                "the engine rejected the request (usage or configuration error); no report was "
                + "produced.",
                command,
                result.ExitCode,
                result.StandardOutput,
                result.StandardError);
        }

        if (result.ExitCode is not (ExitClean or ExitFindings))
        {
            throw new ToolException(
                $"the engine exited with {result.ExitCode}, which is outside its documented exit "
                + "codes (0 clean, 1 findings, 2 usage).",
                command,
                result.ExitCode,
                result.StandardOutput,
                result.StandardError);
        }

        return ParseReport(result.StandardOutput, command, result.ExitCode, result.StandardError);
    }

    private static async Task<ProcessResult> SpawnAsync(
        Engine engine,
        IReadOnlyList<string> arguments,
        string? standardInput,
        ValidationOptions options,
        CancellationToken cancellationToken)
    {
        var command = engine.BuildCommand(arguments, standardInput is not null, options.WorkingDirectory);
        var startInfo = new ProcessStartInfo(command[0])
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = standardInput is not null,
            UseShellExecute = false,
        };

        // An argument list, never a shell string: paths contain spaces.
        foreach (var argument in command.Skip(1))
        {
            startInfo.ArgumentList.Add(argument);
        }

        if (options.WorkingDirectory is { Length: > 0 } directory)
        {
            startInfo.WorkingDirectory = directory;
        }

        if (options.Environment is not null)
        {
            startInfo.Environment.Clear();
            foreach (var (key, value) in options.Environment)
            {
                startInfo.Environment[key] = value;
            }
        }

        using var process = new Process { StartInfo = startInfo };
        try
        {
            process.Start();
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or IOException)
        {
            throw new CimVocabCheckException(
                $"could not start the engine ({engine.Describe()}): {error.Message}", error);
        }

        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        if (options.Timeout is { } timeout)
        {
            deadline.CancelAfter(timeout);
        }

        // Read both pipes concurrently: a large report would otherwise fill a pipe buffer and
        // deadlock the child against a parent that is waiting for it to exit.
        var standardOutputTask = process.StandardOutput.ReadToEndAsync(deadline.Token);
        var standardErrorTask = process.StandardError.ReadToEndAsync(deadline.Token);

        if (standardInput is not null)
        {
            try
            {
                await process.StandardInput.WriteAsync(standardInput.AsMemory(), deadline.Token)
                    .ConfigureAwait(false);
            }
            catch (IOException)
            {
                // The engine stopped reading; its exit code explains why.
            }
            finally
            {
                process.StandardInput.Close();
            }
        }

        try
        {
            await process.WaitForExitAsync(deadline.Token).ConfigureAwait(false);
            return new ProcessResult(
                process.ExitCode,
                await standardOutputTask.ConfigureAwait(false),
                await standardErrorTask.ConfigureAwait(false));
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            Kill(process);
            throw new EngineTimeoutException(
                $"the engine did not finish within {options.Timeout?.TotalSeconds:0.#}s.",
                command);
        }
        catch (OperationCanceledException)
        {
            Kill(process);
            throw;
        }
    }

    private static void Kill(Process process)
    {
        try
        {
            process.Kill(entireProcessTree: true);
        }
        catch (Exception error) when (error is InvalidOperationException or NotSupportedException)
        {
            // Already gone, or the platform will not kill a tree; nothing useful left to do.
        }
    }

    /// <summary>
    /// Runs an asynchronous call to completion from synchronous code.
    /// </summary>
    /// <remarks>
    /// On a thread pool thread, so a caller with a synchronization context cannot deadlock.
    /// </remarks>
    private static T Block<T>(Func<Task<T>> work) =>
        Task.Run(work).GetAwaiter().GetResult();

    private sealed record ProcessResult(int ExitCode, string StandardOutput, string StandardError);
}

/// <summary>
/// Everything a validation call can vary.
/// </summary>
public sealed record ValidationOptions
{
    /// <summary>RDFS profile file(s), or directories of them.</summary>
    public IReadOnlyList<string> Schema { get; init; } = [];

    /// <summary>An <c>opencgmes.jsonc</c>; omitted, the engine discovers the nearest one.</summary>
    public string? Config { get; init; }

    /// <summary>A SPARQL endpoint to load the schema and named-graph mapping from.</summary>
    public string? Endpoint { get; init; }

    /// <summary>Fail rather than fall back to a syntax-only check when the endpoint has no schema.</summary>
    public bool StrictEndpoint { get; init; }

    /// <summary>Profile IRIs to restrict validation to.</summary>
    public IReadOnlyList<string> Profiles { get; init; } = [];

    /// <summary>One of <see cref="CimVocabCheck.Strictness"/>; overrides the config file.</summary>
    public string? Strictness { get; init; }

    /// <summary>
    /// Include WARN and INFO findings. <c>true</c> by default, unlike the CLI: a library hands
    /// back everything and lets the caller filter, rather than discarding findings the caller
    /// cannot then recover.
    /// </summary>
    public bool Verbose { get; init; } = true;

    /// <summary>An engine to use, instead of discovering one.</summary>
    public Engine? Engine { get; init; }

    /// <summary>A path to a JAR or binary to use, instead of discovering one.</summary>
    public string? EnginePath { get; init; }

    /// <summary>Working directory the engine runs in; inputs are resolved against it.</summary>
    public string? WorkingDirectory { get; init; }

    /// <summary>Environment for the child process and for engine discovery.</summary>
    public IReadOnlyDictionary<string, string>? Environment { get; init; }

    /// <summary>How long to wait before giving up on the engine.</summary>
    public TimeSpan? Timeout { get; init; }

    /// <summary>Take the Docker fallback out of the discovery chain.</summary>
    public bool NoDocker { get; init; }

    /// <summary>Further CLI flags, for options newer than this package.</summary>
    public IReadOnlyList<string> ExtraArguments { get; init; } = [];

    /// <summary>The engine these options select, discovering one when none was given.</summary>
    /// <returns>The engine to run.</returns>
    public Engine ResolveEngine() =>
        Engine ?? CimVocabCheck.Engine.Discover(new EngineDiscovery
        {
            EnginePath = EnginePath,
            Environment = Environment,
            WorkingDirectory = WorkingDirectory,
            NoDocker = NoDocker,
        });
}

/// <summary>
/// Values accepted by <see cref="ValidationOptions.Strictness"/>.
/// </summary>
/// <remarks>
/// Unlike the rule codes this is a closed set owned by the CLI rather than by the report
/// contract — an unknown value is rejected by the engine with exit code 2.
/// </remarks>
public static class Strictness
{
    /// <summary>Suppress everything except unknown-term and syntax errors.</summary>
    public const string Permissive = "permissive";

    /// <summary>The engine's own default severities.</summary>
    public const string Default = "default";

    /// <summary>Promote warnings to errors.</summary>
    public const string Strict = "strict";

    /// <summary>Promote warnings and infos to errors.</summary>
    public const string Pedantic = "pedantic";
}
