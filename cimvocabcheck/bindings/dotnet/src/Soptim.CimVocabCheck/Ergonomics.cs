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

using System.Globalization;

namespace Soptim.CimVocabCheck;

/// <summary>
/// One annotation together with the input it was reported against.
/// </summary>
/// <remarks>
/// The report nests annotations under their file; most callers want them flat and still need to
/// know where each came from.
/// </remarks>
/// <param name="File">The input, exactly as it was passed to the engine.</param>
/// <param name="Annotation">The finding itself.</param>
public sealed record Finding(string File, Annotation Annotation)
{
    /// <summary>The finding's severity.</summary>
    public string Severity => Annotation.Severity;

    /// <summary>The finding's rule code.</summary>
    public string Code => Annotation.Code;

    /// <summary>The finding's human-readable message.</summary>
    public string Message => Annotation.Message;

    /// <inheritdoc/>
    public override string ToString() =>
        $"{Annotation.Location(File)}: {Annotation.Severity}[{Annotation.Code}] {Annotation.Message}";
}

/// <summary>
/// Hand-written conveniences on the generated <see cref="Annotation"/>.
/// </summary>
public static class AnnotationExtensions
{
    /// <summary>True for an ERROR-severity finding.</summary>
    /// <param name="annotation">The finding.</param>
    /// <returns>Whether it is an error.</returns>
    public static bool IsError(this Annotation annotation) =>
        Require(annotation).Severity == CimVocabCheck.Severity.Error;

    /// <summary>True for a WARN-severity finding.</summary>
    /// <param name="annotation">The finding.</param>
    /// <returns>Whether it is a warning.</returns>
    public static bool IsWarning(this Annotation annotation) =>
        Require(annotation).Severity == CimVocabCheck.Severity.Warn;

    /// <summary>True for an INFO-severity finding.</summary>
    /// <param name="annotation">The finding.</param>
    /// <returns>Whether it is informational.</returns>
    public static bool IsInfo(this Annotation annotation) =>
        Require(annotation).Severity == CimVocabCheck.Severity.Info;

    /// <summary>
    /// False for a code added by an engine newer than this package.
    /// </summary>
    /// <remarks>
    /// Such a finding is still a valid finding — the contract adds codes in minor versions and
    /// requires consumers to treat an unknown one as a generic finding, never as an error.
    /// </remarks>
    /// <param name="annotation">The finding.</param>
    /// <returns>Whether this package knows the code.</returns>
    public static bool IsKnownCode(this Annotation annotation) =>
        CimVocabCheck.Code.All.Contains(Require(annotation).Code);

    /// <summary>
    /// Renders <c>file:line:col</c>, omitting the parts the engine could not resolve.
    /// </summary>
    /// <param name="annotation">The finding.</param>
    /// <param name="file">The input it was reported against.</param>
    /// <returns>A compiler-style location.</returns>
    public static string Location(this Annotation annotation, string file) =>
        Require(annotation) switch
        {
            { Line: { } line, Column: { } column } =>
                string.Create(CultureInfo.InvariantCulture, $"{file}:{line}:{column}"),
            { Line: { } line } => string.Create(CultureInfo.InvariantCulture, $"{file}:{line}"),
            _ => file,
        };

    private static Annotation Require(Annotation annotation)
    {
        ArgumentNullException.ThrowIfNull(annotation);
        return annotation;
    }
}

/// <summary>
/// Hand-written conveniences on the generated <see cref="FileResult"/>.
/// </summary>
public static class FileResultExtensions
{
    /// <summary>This input's findings, each carrying the file it came from.</summary>
    /// <param name="result">The input's result.</param>
    /// <returns>Its findings.</returns>
    public static IEnumerable<Finding> Findings(this FileResult result)
    {
        ArgumentNullException.ThrowIfNull(result);
        return result.Annotations.Select(annotation => new Finding(result.File, annotation));
    }
}

/// <summary>
/// Hand-written conveniences on the generated <see cref="Report"/>.
/// </summary>
public static class ReportExtensions
{
    /// <summary>True when no input has an ERROR-severity finding.</summary>
    /// <param name="report">The report.</param>
    /// <returns>Whether every input is valid.</returns>
    public static bool IsOk(this Report report) => Require(report).Results.All(r => r.Valid);

    /// <summary>The contract major this document declares — what compatibility is judged on.</summary>
    /// <param name="report">The report.</param>
    /// <returns>The major, or <c>null</c> when it is not a number.</returns>
    public static int? ContractMajor(this Report report) =>
        int.TryParse(
            Require(report).ContractVersion.Split('.')[0],
            CultureInfo.InvariantCulture,
            out var major)
            ? major
            : null;

    /// <summary>All findings across all inputs, in the order the inputs were given.</summary>
    /// <param name="report">The report.</param>
    /// <returns>Every finding.</returns>
    public static IEnumerable<Finding> Findings(this Report report) =>
        Require(report).Results.SelectMany(FileResultExtensions.Findings);

    /// <summary>The findings of one severity.</summary>
    /// <param name="report">The report.</param>
    /// <param name="severity">The severity to keep.</param>
    /// <returns>The matching findings.</returns>
    public static IEnumerable<Finding> OfSeverity(this Report report, string severity) =>
        report.Findings().Where(f => f.Severity == severity);

    /// <summary>The findings of one rule code.</summary>
    /// <param name="report">The report.</param>
    /// <param name="code">The code to keep.</param>
    /// <returns>The matching findings.</returns>
    public static IEnumerable<Finding> OfCode(this Report report, string code) =>
        report.Findings().Where(f => f.Code == code);

    /// <summary>Every finding at least as severe as <paramref name="floor"/>.</summary>
    /// <param name="report">The report.</param>
    /// <param name="floor">The least severity to keep.</param>
    /// <returns>The matching findings.</returns>
    public static IEnumerable<Finding> AtLeast(this Report report, string floor) =>
        report.Findings().Where(f => Severities.AtLeast(f.Severity, floor));

    /// <summary>The ERROR-severity findings.</summary>
    /// <param name="report">The report.</param>
    /// <returns>The errors.</returns>
    public static IEnumerable<Finding> Errors(this Report report) =>
        report.OfSeverity(CimVocabCheck.Severity.Error);

    /// <summary>The WARN-severity findings.</summary>
    /// <param name="report">The report.</param>
    /// <returns>The warnings.</returns>
    public static IEnumerable<Finding> Warnings(this Report report) =>
        report.OfSeverity(CimVocabCheck.Severity.Warn);

    /// <summary>The INFO-severity findings.</summary>
    /// <param name="report">The report.</param>
    /// <returns>The informational findings.</returns>
    public static IEnumerable<Finding> Infos(this Report report) =>
        report.OfSeverity(CimVocabCheck.Severity.Info);

    /// <summary>The inputs that have at least one ERROR-severity finding.</summary>
    /// <param name="report">The report.</param>
    /// <returns>Their names, as they were passed to the engine.</returns>
    public static IEnumerable<string> InvalidFiles(this Report report) =>
        Require(report).Results.Where(r => !r.Valid).Select(r => r.File);

    /// <summary>The result for one input, addressed exactly as it was passed to the engine.</summary>
    /// <param name="report">The report.</param>
    /// <param name="file">The input to look up.</param>
    /// <returns>Its result, or <c>null</c>.</returns>
    public static FileResult? ForFile(this Report report, string file) =>
        Require(report).Results.FirstOrDefault(r => r.File == file);

    private static Report Require(Report report)
    {
        ArgumentNullException.ThrowIfNull(report);
        return report;
    }
}

/// <summary>
/// Comparing severities without assuming the contract will never gain one.
/// </summary>
public static class Severities
{
    /// <summary>
    /// True when <paramref name="severity"/> is at least as severe as <paramref name="floor"/>.
    /// </summary>
    /// <remarks>
    /// An unknown severity sorts below INFO rather than throwing: the contract may grow, and a
    /// consumer that falls over on an unrecognised value is worse than one that under-reports it.
    /// </remarks>
    /// <param name="severity">The severity to test.</param>
    /// <param name="floor">The least severity that counts.</param>
    /// <returns>Whether it clears the floor.</returns>
    public static bool AtLeast(string severity, string floor)
    {
        var order = new[] { Severity.Info, Severity.Warn, Severity.Error };
        var actual = Array.IndexOf(order, severity);
        var required = Array.IndexOf(order, floor);
        return actual >= 0 && required >= 0 && actual >= required;
    }
}
