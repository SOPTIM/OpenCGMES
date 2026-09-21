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

namespace Soptim.CimVocabCheck;

/// <summary>
/// Constants of the published report contract.
/// </summary>
public static class Contract
{
    /// <summary>
    /// Report contract major this binding targets; any report declaring it is accepted.
    /// </summary>
    public const int Major = 1;

    /// <summary>
    /// Canonical URL of the schema these types were generated from.
    /// </summary>
    public const string SchemaId =
        "https://raw.githubusercontent.com/SOPTIM/OpenCGMES/main/cimvocabcheck/schemas/" +
        "cimvocabcheck-report-1.schema.json";
}

/// <summary>
/// Severity after --strictness has been applied.
/// </summary>
/// <remarks>
/// Constants rather than an enum: the contract adds members in minor versions and obliges a consumer to
/// tolerate one it does not know.
/// </remarks>
public static class Severity
{
    /// <summary>The <c>ERROR</c> severity.</summary>
    public const string Error = "ERROR";

    /// <summary>The <c>WARN</c> severity.</summary>
    public const string Warn = "WARN";

    /// <summary>The <c>INFO</c> severity.</summary>
    public const string Info = "INFO";

    /// <summary>Every severity this binding knows, in schema order.</summary>
    public static readonly IReadOnlyList<string> All =
    [
        Error,
        Warn,
        Info,
    ];
}

/// <summary>
/// Stable identifier of the rule that triggered — the key automation should switch on. The catalogue is
/// documented at https://opencgmes.soptim.de/cimvocabcheck/validation-checks. New codes are added in minor
/// contract versions, so consumers must treat an unrecognised code as a generic finding rather than an error.
/// </summary>
/// <remarks>
/// Constants rather than an enum: the contract adds members in minor versions and obliges a consumer to
/// tolerate one it does not know.
/// </remarks>
public static class Code
{
    /// <summary>The <c>SYNTAX_ERROR</c> code.</summary>
    public const string SyntaxError = "SYNTAX_ERROR";

    /// <summary>The <c>UNKNOWN_CLASS</c> code.</summary>
    public const string UnknownClass = "UNKNOWN_CLASS";

    /// <summary>The <c>UNKNOWN_PROPERTY</c> code.</summary>
    public const string UnknownProperty = "UNKNOWN_PROPERTY";

    /// <summary>The <c>UNKNOWN_VOCABULARY_TERM</c> code.</summary>
    public const string UnknownVocabularyTerm = "UNKNOWN_VOCABULARY_TERM";

    /// <summary>The <c>GRAPH_NOT_CONFIGURED</c> code.</summary>
    public const string GraphNotConfigured = "GRAPH_NOT_CONFIGURED";

    /// <summary>The <c>UNSUPPORTED_DYNAMIC_PROPERTY</c> code.</summary>
    public const string UnsupportedDynamicProperty = "UNSUPPORTED_DYNAMIC_PROPERTY";

    /// <summary>The <c>QUERY_IMPLIED_TYPE</c> code.</summary>
    public const string QueryImpliedType = "QUERY_IMPLIED_TYPE";

    /// <summary>The <c>DATATYPE_MISMATCH</c> code.</summary>
    public const string DatatypeMismatch = "DATATYPE_MISMATCH";

    /// <summary>The <c>PROPERTY_NOT_ALLOWED_FOR_CLASS</c> code.</summary>
    public const string PropertyNotAllowedForClass = "PROPERTY_NOT_ALLOWED_FOR_CLASS";

    /// <summary>The <c>NODE_KIND_INCOMPATIBLE_WITH_RANGE</c> code.</summary>
    public const string NodeKindIncompatibleWithRange = "NODE_KIND_INCOMPATIBLE_WITH_RANGE";

    /// <summary>The <c>DATATYPE_INCOMPATIBLE_WITH_RANGE</c> code.</summary>
    public const string DatatypeIncompatibleWithRange = "DATATYPE_INCOMPATIBLE_WITH_RANGE";

    /// <summary>The <c>CLASS_INCOMPATIBLE_WITH_RANGE</c> code.</summary>
    public const string ClassIncompatibleWithRange = "CLASS_INCOMPATIBLE_WITH_RANGE";

    /// <summary>The <c>INVALID_CARDINALITY</c> code.</summary>
    public const string InvalidCardinality = "INVALID_CARDINALITY";

    /// <summary>The <c>INVALID_ENUM_VALUE</c> code.</summary>
    public const string InvalidEnumValue = "INVALID_ENUM_VALUE";

    /// <summary>The <c>INVALID_VALUE_RANGE</c> code.</summary>
    public const string InvalidValueRange = "INVALID_VALUE_RANGE";

    /// <summary>The <c>UNKNOWN_TERM_IN_EXPRESSION</c> code.</summary>
    public const string UnknownTermInExpression = "UNKNOWN_TERM_IN_EXPRESSION";

    /// <summary>The <c>CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY</c> code.</summary>
    public const string CardinalityIncompatibleWithMultiplicity = "CARDINALITY_INCOMPATIBLE_WITH_MULTIPLICITY";

    /// <summary>The <c>PROJECTED_VARIABLE_UNBOUND</c> code.</summary>
    public const string ProjectedVariableUnbound = "PROJECTED_VARIABLE_UNBOUND";

    /// <summary>The <c>UNUSED_VARIABLE</c> code.</summary>
    public const string UnusedVariable = "UNUSED_VARIABLE";

    /// <summary>Every code this binding knows, in schema order.</summary>
    public static readonly IReadOnlyList<string> All =
    [
        SyntaxError,
        UnknownClass,
        UnknownProperty,
        UnknownVocabularyTerm,
        GraphNotConfigured,
        UnsupportedDynamicProperty,
        QueryImpliedType,
        DatatypeMismatch,
        PropertyNotAllowedForClass,
        NodeKindIncompatibleWithRange,
        DatatypeIncompatibleWithRange,
        ClassIncompatibleWithRange,
        InvalidCardinality,
        InvalidEnumValue,
        InvalidValueRange,
        UnknownTermInExpression,
        CardinalityIncompatibleWithMultiplicity,
        ProjectedVariableUnbound,
        UnusedVariable,
    ];
}
