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

using static Soptim.CimVocabCheck.Tests.TestSupport;

namespace Soptim.CimVocabCheck.Tests;

/// <summary>
/// End-to-end against a real engine.
/// </summary>
/// <remarks>
/// The scripted tests pin what the package sends and how it reacts; these pin that a real engine
/// actually answers that way. They run in syntax-only mode so they need no CGMES profile
/// library, and they skip when the machine has no engine at all.
/// </remarks>
public sealed class EngineIntegrationTests
{
    /// <summary>
    /// A workspace and options pointing at an engine that speaks this package's contract.
    /// </summary>
    /// <remarks>
    /// Discovery can land on an engine too old for the contract — most plausibly the published
    /// container image, which lags a change made in this repository. That is not a bug in the
    /// package and must not read as one, so the engine is probed and the test skips.
    /// <c>CIMVOCABCHECK_TESTS_REQUIRE_ENGINE</c> turns the skip into a failure; CI sets it.
    /// </remarks>
    private static (TempDir Workspace, ValidationOptions Options) Engine()
    {
        string? reason = null;
        try
        {
            Validator.ValidateText(CleanQuery);
        }
        catch (EngineNotFoundException error)
        {
            reason = $"no engine available: {error.Message}";
        }
        catch (ReportParseException error)
        {
            reason = $"the discovered engine does not speak the report contract: {error.Message}";
        }
        catch (CimVocabCheckException error)
        {
            reason = $"the discovered engine could not be run: {error.Message}";
        }

        if (reason is not null)
        {
            Assert.False(
                System.Environment.GetEnvironmentVariable(RequireEngine) is { Length: > 0 },
                $"{reason} ({RequireEngine} is set)");
            Assert.Skip(reason);
        }

        var workspace = TempDir.Workspace();
        return (workspace, new ValidationOptions { WorkingDirectory = workspace.Path });
    }

    [Fact]
    public void ACleanQueryProducesACleanReport()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write("clean.rq", CleanQuery);

        var report = Validator.Validate([input], options);

        Assert.True(report.IsOk());
        Assert.Equal(1, report.Summary.Files);
        Assert.Equal(1, report.ContractMajor());
        Assert.Equal("cimvocabcheck", report.Tool.Name);
        Assert.Empty(report.Findings());
    }

    [Fact]
    public void ABrokenQueryComesBackAsFindings()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write("broken.rq", BrokenQuery);

        var report = Validator.Validate([input], options);

        Assert.False(report.IsOk());
        Assert.Equal(["broken.rq"], report.InvalidFiles());
        Assert.Equal(Code.SyntaxError, report.Errors().First().Code);
        Assert.Equal("broken.rq", report.Errors().First().File);
    }

    [Fact]
    public void EveryInputIsValidatedInOneRun()
    {
        // The whole point of the batch API: one schema load for all of them.
        var (workspace, options) = Engine();
        using var _ = workspace;
        string[] inputs =
        [
            workspace.Write("clean.rq", CleanQuery),
            workspace.Write("broken.rq", BrokenQuery),
            workspace.Write("warning.rq", WarningQuery),
        ];

        var report = Validator.Validate(inputs, options);

        Assert.Equal(inputs, report.Results.Select(r => r.File));
        Assert.Equal(3, report.Summary.Files);
        Assert.Equal(1, report.Summary.Invalid);
    }

    [Fact]
    public void TheSummaryAccountsForExactlyWhatTheReportContains()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        string[] inputs =
        [
            workspace.Write("broken.rq", BrokenQuery),
            workspace.Write("warning.rq", WarningQuery),
        ];

        var report = Validator.Validate(inputs, options);

        Assert.Equal(report.Summary.Errors, report.Errors().Count());
        Assert.Equal(report.Summary.Warnings, report.Warnings().Count());
        Assert.Equal(report.Summary.Infos, report.Infos().Count());
        Assert.True(report.Summary.Warnings > 0, "the fixture must produce WARN findings");
    }

    [Fact]
    public void WarningsAreDroppedWhenTheCallerAsksForTheCliDefault()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write("warning.rq", WarningQuery);

        var verbose = Validator.Validate([input], options);
        var quiet = Validator.Validate([input], options with { Verbose = false });

        Assert.NotEmpty(verbose.Warnings());
        Assert.Empty(quiet.Warnings());
        Assert.Equal(0, quiet.Summary.Warnings);
    }

    [Fact]
    public void StrictnessPromotesWarningsToErrors()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write("warning.rq", WarningQuery);

        var standard = Validator.Validate([input], options);
        var strict = Validator.Validate(
            [input], options with { Strictness = CimVocabCheck.Strictness.Strict });

        Assert.True(standard.IsOk());
        Assert.False(strict.IsOk());
        Assert.All(strict.Findings(), finding => Assert.Equal(Severity.Error, finding.Severity));
    }

    [Fact]
    public void AQueryInMemoryIsValidatedOverStandardInput()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;

        var report = Validator.ValidateText(BrokenQuery, options);

        Assert.False(report.IsOk());
        Assert.Equal(Validator.StandardInputName, report.Results[0].File);
    }

    [Fact]
    public void APathWithASpaceSurvives()
    {
        // The reason this package never builds a shell string.
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write(Path.Combine("my queries", "a b.rq"), CleanQuery);

        var report = Validator.Validate([input], options);

        Assert.True(report.IsOk());
        Assert.Equal(input, report.Results[0].File);
    }

    [Fact]
    public void AShaclShapeFileIsValidatedAsTurtle()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write("shapes.ttl", "this is not turtle at all\n");

        var report = Validator.Validate([input], options);

        Assert.False(report.IsOk());
    }

    [Fact]
    public void AConfigurationErrorIsThrownRatherThanReturned()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;
        var input = workspace.Write("clean.rq", CleanQuery);

        var error = Assert.Throws<ToolException>(
            () => Validator.Validate([input], options with { Strictness = "bogus" }));

        Assert.Contains("strictness", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task ThePackageReportsTheEngineVersion()
    {
        var (workspace, options) = Engine();
        using var _ = workspace;

        var version = await Validator.ToolVersionAsync(options, TestContext.Current.CancellationToken);

        Assert.NotEmpty(version);
    }
}
