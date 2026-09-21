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

// ------------------------------------------------------------------------------------------
// GENERATED FILE - DO NOT EDIT.
// Produced from cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json
// by cimvocabcheck/bindings/codegen/generate_models.py.
// Conveniences belong in Ergonomics.cs, which survives regeneration.
// ------------------------------------------------------------------------------------------

// A ".g.cs" file is auto-generated as far as the compiler is concerned, which switches the
// nullable context off unless the file turns it back on itself.
#nullable enable

using System.Text.Json;
using System.Text.Json.Serialization;

namespace Soptim.CimVocabCheck;

/// <summary>
/// One finding. Position and term are present whenever the engine could resolve them.
/// </summary>
public sealed record Annotation
{
    /// <summary>
    /// Severity after --strictness has been applied. See <see cref="Severity"/> for the values known to this
    /// binding.
    /// </summary>
    [JsonPropertyName("severity")]
    public required string Severity { get; init; }

    /// <summary>
    /// Stable identifier of the rule that triggered — the key automation should switch on. The catalogue is
    /// documented at https://opencgmes.soptim.de/cimvocabcheck/validation-checks. New codes are added in
    /// minor contract versions, so consumers must treat an unrecognised code as a generic finding rather than
    /// an error. See <see cref="Code"/> for the values known to this binding.
    /// </summary>
    [JsonPropertyName("code")]
    public required string Code { get; init; }

    /// <summary>
    /// Human-readable rendering. Not stable across releases — key automation off "code", never off this text.
    /// </summary>
    [JsonPropertyName("message")]
    public required string Message { get; init; }

    /// <summary>
    /// 1-based line in the input file. Findings inside embedded SPARQL (sh:select / sh:ask) are mapped back
    /// to their line in the Turtle source. Absent when the engine could not resolve a position.
    /// </summary>
    [JsonPropertyName("line")]
    public int? Line { get; init; }

    /// <summary>
    /// 1-based column, counted in UTF-16 code units. Absent when unresolved.
    /// </summary>
    [JsonPropertyName("column")]
    public int? Column { get; init; }

    /// <summary>
    /// The offending RDF term (class IRI, property IRI, …). Absent when the finding is not about a single
    /// term.
    /// </summary>
    [JsonPropertyName("term")]
    public string? Term { get; init; }

    /// <summary>
    /// Named graph the finding occurred in, under per-graph profile scoping. Absent for default-graph
    /// context.
    /// </summary>
    [JsonPropertyName("graph")]
    public string? Graph { get; init; }

    /// <summary>
    /// For UNKNOWN_CLASS / UNKNOWN_PROPERTY: profiles in which the term DOES exist. A hint that the wrong
    /// profile is in scope rather than a typo. Absent when empty.
    /// </summary>
    private readonly IReadOnlyList<string>? _foundInOtherProfiles;

    /// <summary>
    /// For UNKNOWN_CLASS / UNKNOWN_PROPERTY: profiles in which the term DOES exist. A hint that the wrong
    /// profile is in scope rather than a typo. Absent when empty.
    /// </summary>
    [JsonPropertyName("foundInOtherProfiles")]
    public IReadOnlyList<string> FoundInOtherProfiles
    {
        get => _foundInOtherProfiles ?? [];
        init => _foundInOtherProfiles = value;
    }

    /// <summary>
    /// Fields the engine emitted that this binding does not know about.
    /// </summary>
    /// <remarks>
    /// The contract only ever adds within a major version, so a field this
    /// binding predates is still meaningful to a caller that knows about it.
    /// </remarks>
    /// <remarks>
    /// Settable rather than init-only: System.Text.Json refuses to bind
    /// extension data through a constructor parameter, which is how it
    /// populates an init-only member.
    /// </remarks>
    [JsonExtensionData]
    public IDictionary<string, JsonElement>? Extra { get; set; }
}

/// <summary>
/// One validated input and the findings reported against it.
/// </summary>
public sealed record FileResult
{
    /// <summary>
    /// The input as it was passed on the command line, or "&lt;stdin&gt;" for input read from stdin. Invoke
    /// the CLI with repository-relative paths for findings to line up with a source tree.
    /// </summary>
    [JsonPropertyName("file")]
    public required string File { get; init; }

    /// <summary>
    /// False when the input has at least one ERROR-severity finding after strictness is applied. Independent
    /// of --verbose.
    /// </summary>
    [JsonPropertyName("valid")]
    public required bool Valid { get; init; }

    /// <summary>
    /// Findings for this input; empty when it is clean, or when --verbose is off and only WARN/INFO findings
    /// were produced.
    /// </summary>
    [JsonPropertyName("annotations")]
    public required IReadOnlyList<Annotation> Annotations { get; init; }

    /// <summary>
    /// Fields the engine emitted that this binding does not know about.
    /// </summary>
    /// <remarks>
    /// The contract only ever adds within a major version, so a field this
    /// binding predates is still meaningful to a caller that knows about it.
    /// </remarks>
    /// <remarks>
    /// Settable rather than init-only: System.Text.Json refuses to bind
    /// extension data through a constructor parameter, which is how it
    /// populates an init-only member.
    /// </remarks>
    [JsonExtensionData]
    public IDictionary<string, JsonElement>? Extra { get; set; }
}

/// <summary>
/// Which engine produced the report.
/// </summary>
public sealed record Tool
{
    /// <summary>
    /// Always "cimvocabcheck".
    /// </summary>
    [JsonPropertyName("name")]
    public required string Name { get; init; }

    /// <summary>
    /// Released tool version, or "unknown" when the engine runs from a checkout rather than a packaged JAR.
    /// </summary>
    [JsonPropertyName("version")]
    public required string Version { get; init; }

    /// <summary>
    /// Fields the engine emitted that this binding does not know about.
    /// </summary>
    /// <remarks>
    /// The contract only ever adds within a major version, so a field this
    /// binding predates is still meaningful to a caller that knows about it.
    /// </remarks>
    /// <remarks>
    /// Settable rather than init-only: System.Text.Json refuses to bind
    /// extension data through a constructor parameter, which is how it
    /// populates an init-only member.
    /// </remarks>
    [JsonExtensionData]
    public IDictionary<string, JsonElement>? Extra { get; set; }
}

/// <summary>
/// Counts over this document. The finding counts are taken AFTER the --verbose filter, so they always account
/// for exactly what "results" contains; "valid"/"invalid" are decided before filtering and are unaffected by
/// it.
/// </summary>
public sealed record Summary
{
    /// <summary>
    /// Number of validated inputs.
    /// </summary>
    [JsonPropertyName("files")]
    public required int Files { get; init; }

    /// <summary>
    /// Inputs with no ERROR-severity finding.
    /// </summary>
    [JsonPropertyName("valid")]
    public required int Valid { get; init; }

    /// <summary>
    /// Inputs with at least one ERROR-severity finding.
    /// </summary>
    [JsonPropertyName("invalid")]
    public required int Invalid { get; init; }

    /// <summary>
    /// ERROR-severity findings in this document.
    /// </summary>
    [JsonPropertyName("errors")]
    public required int Errors { get; init; }

    /// <summary>
    /// WARN-severity findings in this document.
    /// </summary>
    [JsonPropertyName("warnings")]
    public required int Warnings { get; init; }

    /// <summary>
    /// INFO-severity findings in this document.
    /// </summary>
    [JsonPropertyName("infos")]
    public required int Infos { get; init; }

    /// <summary>
    /// Fields the engine emitted that this binding does not know about.
    /// </summary>
    /// <remarks>
    /// The contract only ever adds within a major version, so a field this
    /// binding predates is still meaningful to a caller that knows about it.
    /// </remarks>
    /// <remarks>
    /// Settable rather than init-only: System.Text.Json refuses to bind
    /// extension data through a constructor parameter, which is how it
    /// populates an init-only member.
    /// </remarks>
    [JsonExtensionData]
    public IDictionary<string, JsonElement>? Extra { get; set; }
}

/// <summary>
/// The document CIMVocabCheck writes with --format json. This is the published integration contract: non-Java
/// consumers generate their result types from this schema. Within contract major version 1, fields, output
/// formats and diagnostic codes are only ever ADDED — nothing is renamed or removed — so a consumer must
/// ignore properties and enum members it does not know. Breaking changes require a new major (a new file,
/// cimvocabcheck-report-2.schema.json). The SARIF report (--format sarif) follows the OASIS SARIF 2.1.0
/// schema instead and is not described here.
/// </summary>
public sealed record Report
{
    /// <summary>
    /// Version of this report contract, "&lt;major&gt;.&lt;minor&gt;" — independent of the tool version. A
    /// consumer should accept any report whose major matches the one it was written against.
    /// </summary>
    [JsonPropertyName("contractVersion")]
    public required string ContractVersion { get; init; }

    /// <summary>
    /// Which engine produced the report.
    /// </summary>
    [JsonPropertyName("tool")]
    public required Tool Tool { get; init; }

    /// <summary>
    /// Counts over this document. The finding counts are taken AFTER the --verbose filter, so they always
    /// account for exactly what "results" contains; "valid"/"invalid" are decided before filtering and are
    /// unaffected by it.
    /// </summary>
    [JsonPropertyName("summary")]
    public required Summary Summary { get; init; }

    /// <summary>
    /// One entry per validated input, in the order the inputs were given.
    /// </summary>
    [JsonPropertyName("results")]
    public required IReadOnlyList<FileResult> Results { get; init; }

    /// <summary>
    /// Fields the engine emitted that this binding does not know about.
    /// </summary>
    /// <remarks>
    /// The contract only ever adds within a major version, so a field this
    /// binding predates is still meaningful to a caller that knows about it.
    /// </remarks>
    /// <remarks>
    /// Settable rather than init-only: System.Text.Json refuses to bind
    /// extension data through a constructor parameter, which is how it
    /// populates an init-only member.
    /// </remarks>
    [JsonExtensionData]
    public IDictionary<string, JsonElement>? Extra { get; set; }
}

/// <summary>
/// Source-generated serialization metadata, so reading a report needs no reflection
/// and the package stays trimming- and AOT-friendly. Rooted at <see cref="Report"/>;
/// the nested types come with it.
/// </summary>
[JsonSourceGenerationOptions(
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    WriteIndented = false)]
[JsonSerializable(typeof(Report))]
internal sealed partial class ReportJsonContext : JsonSerializerContext;
