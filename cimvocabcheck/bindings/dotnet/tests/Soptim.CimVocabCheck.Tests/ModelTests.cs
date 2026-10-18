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

using System.Text.Json;
using static Soptim.CimVocabCheck.Tests.TestSupport;

namespace Soptim.CimVocabCheck.Tests;

/// <summary>
/// The result model: what the contract obliges a consumer to tolerate, and the ergonomics on top.
/// </summary>
public sealed class ModelTests
{
    private const string Finding = """
        {
          "severity": "ERROR",
          "code": "UNKNOWN_CLASS",
          "line": 3,
          "column": 12,
          "term": "http://iec.ch/TC57/CIM100#ACLineSegmentt",
          "graph": "http://example.org/graph",
          "message": "Class does not exist in profile.",
          "foundInOtherProfiles": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
        }
        """;

    /// <summary>
    /// Walks up to the repository's published schema, or <c>null</c> outside a checkout.
    /// </summary>
    private static string? PublishedSchema()
    {
        const string Relative = "schemas/cimvocabcheck-report-1.schema.json";
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory);
             directory is not null;
             directory = directory.Parent)
        {
            var candidate = Path.Combine(directory.FullName, Relative);
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return null;
    }

    private static string OneResult(string annotations, bool valid = false) =>
        ReportJson(
            $$"""
              { "file": "q.rq", "valid": {{(valid ? "true" : "false")}},
                "annotations": [{{annotations}}] }
              """);

    [Fact]
    public void ReadsEveryDocumentedField()
    {
        var report = Validator.ParseReport(OneResult(Finding));

        var annotation = report.Results[0].Annotations[0];
        Assert.Equal(Severity.Error, annotation.Severity);
        Assert.Equal(Code.UnknownClass, annotation.Code);
        Assert.Equal(3, annotation.Line);
        Assert.Equal(12, annotation.Column);
        Assert.EndsWith("ACLineSegmentt", annotation.Term, StringComparison.Ordinal);
        Assert.Equal("http://example.org/graph", annotation.Graph);
        Assert.Single(annotation.FoundInOtherProfiles);
    }

    [Fact]
    public void OmittedOptionalFieldsAreNullNotErrors()
    {
        var report = Validator.ParseReport(
            OneResult("""{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "x" }""", valid: true));

        var annotation = report.Results[0].Annotations[0];
        Assert.Null(annotation.Line);
        Assert.Null(annotation.Column);
        Assert.Null(annotation.Term);
        Assert.Empty(annotation.FoundInOtherProfiles);
    }

    [Fact]
    public void UnknownFieldsAreKeptRatherThanRejected()
    {
        // The contract adds fields in minor versions; a consumer must not break on one it predates.
        var document = OneResult(
            """{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "x", "futureField": 7 }""",
            valid: true)
            .Replace("\"results\": [", "\"futureTopLevel\": true, \"results\": [", StringComparison.Ordinal);

        var report = Validator.ParseReport(document);

        Assert.True(report.Extra!.ContainsKey("futureTopLevel"));
        Assert.Equal(
            7,
            report.Results[0].Annotations[0].Extra!["futureField"].GetInt32());
    }

    [Fact]
    public void UnknownCodeIsAFindingNotAnError()
    {
        // New rule codes ship in minor versions — an unrecognised one must still parse.
        var report = Validator.ParseReport(
            OneResult("""{ "severity": "ERROR", "code": "A_RULE_FROM_THE_FUTURE", "message": "x" }"""));

        var annotation = report.Results[0].Annotations[0];
        Assert.Equal("A_RULE_FROM_THE_FUTURE", annotation.Code);
        Assert.True(annotation.IsError());
        Assert.False(annotation.IsKnownCode());
    }

    [Fact]
    public void KnownCodesCoverThePublishedEnum()
    {
        var path = PublishedSchema();
        Assert.SkipWhen(path is null, "not a repository checkout; the published schema is absent");
        using var schema = JsonDocument.Parse(File.ReadAllText(path!));

        var published = schema.RootElement
            .GetProperty("definitions").GetProperty("code").GetProperty("enum")
            .EnumerateArray().Select(value => value.GetString()!).ToList();

        Assert.Equal(published, Code.All);
    }

    [Fact]
    public void AMissingRequiredFieldIsAParseError()
    {
        var error = Assert.Throws<ReportParseException>(
            () => Validator.ParseReport(OneResult("""{ "severity": "ERROR", "code": "SYNTAX_ERROR" }""")));

        Assert.Contains("Message", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void AnIncompatibleContractMajorIsRefused()
    {
        var document = ReportJson()
            .Replace("\"contractVersion\": \"1.0\"", "\"contractVersion\": \"2.0\"", StringComparison.Ordinal);

        var error = Assert.Throws<ReportParseException>(() => Validator.ParseReport(document));

        Assert.Contains($"contract {Contract.Major}.x", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ANewerContractMinorIsAccepted()
    {
        var document = ReportJson()
            .Replace("\"contractVersion\": \"1.0\"", "\"contractVersion\": \"1.7\"", StringComparison.Ordinal);

        Assert.Equal(1, Validator.ParseReport(document).ContractMajor());
    }

    [Fact]
    public void OutputThatIsNotJsonIsAParseError()
    {
        var error = Assert.Throws<ReportParseException>(
            () => Validator.ParseReport("Exception in thread \"main\"\n"));

        Assert.Contains("not JSON", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void FindingsAreFlatAndCarryTheirFile()
    {
        var report = Validator.ParseReport(ReportJson($$"""
            { "file": "a.rq", "valid": false, "annotations": [{{Finding}}] },
            { "file": "b.rq", "valid": true, "annotations":
              [{ "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "w" }] }
            """));

        Assert.False(report.IsOk());
        Assert.Equal(["a.rq"], report.InvalidFiles());
        Assert.Equal(["a.rq", "b.rq"], report.Findings().Select(f => f.File));
        Assert.Single(report.Errors());
        Assert.Single(report.Warnings());
        Assert.Equal("a.rq", report.OfCode(Code.UnknownClass).First().File);
        Assert.True(report.ForFile("b.rq")!.Valid);
        Assert.Null(report.ForFile("missing.rq"));
    }

    [Fact]
    public void AFindingRendersAsACompilerStyleLine()
    {
        var report = Validator.ParseReport(OneResult(Finding));

        Assert.Equal(
            "q.rq:3:12: ERROR[UNKNOWN_CLASS] Class does not exist in profile.",
            report.Findings().First().ToString());
    }

    [Fact]
    public void LocationOmitsPositionsTheEngineCouldNotResolve()
    {
        var report = Validator.ParseReport(
            OneResult("""{ "severity": "ERROR", "code": "SYNTAX_ERROR", "message": "x" }"""));

        Assert.Equal("q.rq", report.Results[0].Annotations[0].Location("q.rq"));
    }

    [Fact]
    public void ASeverityFloorSelectsEverythingAtLeastThatSevere()
    {
        var report = Validator.ParseReport(OneResult($$"""
            {{Finding}},
            { "severity": "WARN", "code": "UNUSED_VARIABLE", "message": "w" },
            { "severity": "INFO", "code": "QUERY_IMPLIED_TYPE", "message": "i" }
            """));

        Assert.Equal(2, report.AtLeast(Severity.Warn).Count());
        Assert.Single(report.AtLeast(Severity.Error));
        Assert.Equal(3, report.AtLeast(Severity.Info).Count());
    }

    [Fact]
    public void SeverityOrderingToleratesASeverityItDoesNotKnow()
    {
        Assert.True(Severities.AtLeast(Severity.Error, Severity.Warn));
        Assert.False(Severities.AtLeast(Severity.Info, Severity.Warn));
        Assert.False(Severities.AtLeast("CATASTROPHE", Severity.Warn));
    }
}
