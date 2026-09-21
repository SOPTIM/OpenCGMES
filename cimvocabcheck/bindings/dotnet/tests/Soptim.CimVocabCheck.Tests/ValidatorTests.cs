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
/// The command line this package builds, and how it reacts to what comes back.
/// </summary>
/// <remarks>
/// These drive a scripted stand-in rather than the real engine, so they pin the contract between
/// the package and <em>any</em> conforming engine — including the exit codes and the failure
/// modes a real one is hard to provoke into.
/// </remarks>
public sealed class ValidatorTests
{
    [Fact]
    public void TheReportFormatAndEveryOptionReachTheCommandLine()
    {
        var arguments = Validator.BuildArguments(
            ["a.rq", "b.rq"],
            new ValidationOptions
            {
                Schema = ["profiles", "extra.rdf"],
                Config = "opencgmes.jsonc",
                Endpoint = "http://localhost:3030/ds/query",
                StrictEndpoint = true,
                Profiles = ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
                Strictness = CimVocabCheck.Strictness.Strict,
            }).ToList();

        Assert.Equal(["--format", "json"], arguments.Take(2));
        Assert.Contains("--verbose", arguments);
        Assert.Equal(2, arguments.Count(a => a == "--schema"));
        Assert.Equal("opencgmes.jsonc", arguments[arguments.IndexOf("--config") + 1]);
        Assert.Equal("strict", arguments[arguments.IndexOf("--strictness") + 1]);
        Assert.Contains("--strict-endpoint", arguments);
        Assert.Equal(["--", "a.rq", "b.rq"], arguments.TakeLast(3));
    }

    [Fact]
    public void InputsAreSeparatedFromOptionsSoALeadingDashIsStillAFile()
    {
        var arguments = Validator.BuildArguments(["-weird-name.rq"], new ValidationOptions()).ToList();

        Assert.Equal("-weird-name.rq", arguments[arguments.IndexOf("--") + 1]);
    }

    [Fact]
    public void WarningsAreRequestedByDefaultBecauseALibraryShouldNotDropFindings()
    {
        Assert.Contains("--verbose", Validator.BuildArguments(["q.rq"], new ValidationOptions()));
    }

    [Fact]
    public void VerboseCanBeTurnedOffToMatchTheCliDefault()
    {
        Assert.DoesNotContain(
            "--verbose",
            Validator.BuildArguments(["q.rq"], new ValidationOptions { Verbose = false }));
    }

    [Fact]
    public void UnknownOptionsCanBeForwardedWithoutAPackageRelease()
    {
        var arguments = Validator
            .BuildArguments(["q.rq"], new ValidationOptions { ExtraArguments = ["--future-flag", "value"] })
            .ToList();

        Assert.Equal("value", arguments[arguments.IndexOf("--future-flag") + 1]);
    }

    [Fact]
    public void FindingsAreDataNotAnException()
    {
        // Exit code 1 means "the input has errors", which is a report, not a tool failure.
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var document = ReportJson("""
            { "file": "q.rq", "valid": false, "annotations":
              [{ "severity": "ERROR", "code": "SYNTAX_ERROR", "message": "boom" }] }
            """);
        var engine = directory.Scripted("findings", document, exitCode: 1);

        var report = Validator.Validate(["q.rq"], new ValidationOptions { Engine = engine });

        Assert.False(report.IsOk());
        Assert.Single(report.Errors());
    }

    [Fact]
    public void AUsageErrorIsRaisedWithTheEnginesOwnComplaint()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var engine = directory.Scripted(
            "usage", string.Empty, "Error: Unknown strictness level 'bogus'.", 2);

        var error = Assert.Throws<ToolException>(
            () => Validator.Validate(
                ["q.rq"],
                new ValidationOptions { Engine = engine, Strictness = "bogus" }));

        Assert.Contains("Unknown strictness level", error.Message, StringComparison.Ordinal);
        Assert.Equal(2, error.ExitCode);
    }

    [Fact]
    public void AnUndocumentedExitCodeIsReportedAsAnEngineFailure()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var engine = directory.Scripted("crash", string.Empty, "Exception in thread \"main\"", 137);

        var error = Assert.Throws<ToolException>(
            () => Validator.Validate(["q.rq"], new ValidationOptions { Engine = engine }));

        Assert.Contains("outside its documented exit codes", error.Message, StringComparison.Ordinal);
        Assert.Equal(137, error.ExitCode);
    }

    [Fact]
    public void UnparseableOutputCarriesWhatTheEngineActuallyPrinted()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var engine = directory.Scripted("garbage", "not a report at all", "warning: something");

        var error = Assert.Throws<ReportParseException>(
            () => Validator.Validate(["q.rq"], new ValidationOptions { Engine = engine }));

        Assert.Contains("not a report at all", error.StandardOutput, StringComparison.Ordinal);
        Assert.Contains("something", error.StandardError, StringComparison.Ordinal);
    }

    [Fact]
    public void ATimeoutIsItsOwnException()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var engine = new BinaryEngine(directory.Script("slow", "sleep 5"));

        Assert.Throws<EngineTimeoutException>(
            () => Validator.Validate(
                ["q.rq"],
                new ValidationOptions { Engine = engine, Timeout = TimeSpan.FromMilliseconds(300) }));
    }

    [Fact]
    public void AnEngineThatCannotBeStartedIsReportedClearly()
    {
        var engine = new BinaryEngine("/nowhere/does-not-exist");

        var error = Assert.Throws<CimVocabCheckException>(
            () => Validator.Validate(["q.rq"], new ValidationOptions { Engine = engine }));

        Assert.Contains("could not start", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ToolVersionReportsTheEngineNotThePackage()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var engine = directory.Scripted("versioned", "cimvocabcheck 1.4.2");

        var version = await Validator.ToolVersionAsync(
            new ValidationOptions { Engine = engine }, TestContext.Current.CancellationToken);

        Assert.Equal("1.4.2", version);
    }

    [Fact]
    public void TextIsValidatedThroughStandardInput()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        // Echo the query back inside a report, proving it arrived on stdin.
        var engine = new BinaryEngine(directory.Script(
            "stdin",
            """
            read -r line
            cat <<REPORT
            {"contractVersion":"1.0","tool":{"name":"cimvocabcheck","version":"$line"},
             "summary":{"files":0,"valid":0,"invalid":0,"errors":0,"warnings":0,"infos":0},
             "results":[]}
            REPORT
            """));

        var report = Validator.ValidateText("SELECT-MARKER", new ValidationOptions { Engine = engine });

        Assert.Equal("SELECT-MARKER", report.Tool.Version);
    }

    [Fact]
    public void TheChildRunsInTheRequestedWorkingDirectory()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        using var elsewhere = new TempDir();
        var engine = new BinaryEngine(directory.Script(
            "cwd",
            """
            cat <<REPORT
            {"contractVersion":"1.0","tool":{"name":"cimvocabcheck","version":"$(pwd -P)"},
             "summary":{"files":0,"valid":0,"invalid":0,"errors":0,"warnings":0,"infos":0},
             "results":[]}
            REPORT
            """));

        var report = Validator.Validate(
            ["q.rq"],
            new ValidationOptions { Engine = engine, WorkingDirectory = elsewhere.Path });

        Assert.Equal(elsewhere.Path, report.Tool.Version);
    }

    [Fact]
    public async Task ACancelledCallStopsTheEngine()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var engine = new BinaryEngine(directory.Script("slow", "sleep 5"));
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => Validator.ValidateAsync(
                ["q.rq"], new ValidationOptions { Engine = engine }, cancellation.Token));
    }
}
