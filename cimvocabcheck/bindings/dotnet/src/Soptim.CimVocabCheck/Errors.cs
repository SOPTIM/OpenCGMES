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

namespace Soptim.CimVocabCheck;

/// <summary>
/// Base class for every failure this package raises.
/// </summary>
/// <remarks>
/// Findings are data, never exceptions: a query with errors comes back as a
/// <see cref="Report"/> whose <see cref="ReportExtensions.IsOk"/> is <c>false</c>. These are
/// raised only when the engine could not be run, or ran but did not produce a report this
/// package can trust.
/// </remarks>
public class CimVocabCheckException : Exception
{
    /// <summary>Creates the exception.</summary>
    /// <param name="message">What went wrong.</param>
    public CimVocabCheckException(string message)
        : base(message)
    {
    }

    /// <summary>Creates the exception.</summary>
    /// <param name="message">What went wrong.</param>
    /// <param name="innerException">The underlying failure.</param>
    public CimVocabCheckException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }
}

/// <summary>
/// No CIMVocabCheck engine could be discovered.
/// </summary>
public sealed class EngineNotFoundException : CimVocabCheckException
{
    /// <summary>Creates the exception.</summary>
    /// <param name="message">What to do about it.</param>
    /// <param name="searched">Every location that was searched, in order.</param>
    public EngineNotFoundException(string message, IReadOnlyList<string>? searched = null)
        : base(Compose(message, searched ?? []))
    {
        Searched = searched ?? [];
    }

    /// <summary>Every location that was searched, in order.</summary>
    public IReadOnlyList<string> Searched { get; }

    private static string Compose(string message, IReadOnlyList<string> searched)
    {
        if (searched.Count == 0)
        {
            return message;
        }

        var lines = searched.Select((where, index) => $"  {index + 1}. {where}");
        return $"{message}{Environment.NewLine}Searched, in order:{Environment.NewLine}"
            + string.Join(Environment.NewLine, lines);
    }
}

/// <summary>
/// The engine ran but did not deliver a usable report.
/// </summary>
/// <remarks>
/// Covers a usage or configuration failure (exit code 2) and any exit code outside the
/// documented <c>0</c>/<c>1</c>/<c>2</c>.
/// </remarks>
public class ToolException : CimVocabCheckException
{
    /// <summary>Creates the exception.</summary>
    /// <param name="message">What went wrong.</param>
    /// <param name="arguments">The exact command that was run.</param>
    /// <param name="exitCode">The process exit code, when it exited.</param>
    /// <param name="standardOutput">Everything the engine wrote to stdout.</param>
    /// <param name="standardError">Everything the engine wrote to stderr.</param>
    public ToolException(
        string message,
        IReadOnlyList<string>? arguments = null,
        int? exitCode = null,
        string standardOutput = "",
        string standardError = "")
        : base(Compose(message, standardOutput, standardError))
    {
        Arguments = arguments ?? [];
        ExitCode = exitCode;
        StandardOutput = standardOutput;
        StandardError = standardError;
    }

    /// <summary>The exact command that was run.</summary>
    public IReadOnlyList<string> Arguments { get; }

    /// <summary>The process exit code, when it exited.</summary>
    public int? ExitCode { get; }

    /// <summary>Everything the engine wrote to stdout.</summary>
    public string StandardOutput { get; }

    /// <summary>Everything the engine wrote to stderr.</summary>
    public string StandardError { get; }

    private static string Compose(string message, string stdout, string stderr)
    {
        var detail = string.IsNullOrWhiteSpace(stderr) ? stdout.Trim() : stderr.Trim();
        return detail.Length == 0 ? message : $"{message}{Environment.NewLine}{detail}";
    }
}

/// <summary>
/// The engine's output was not a report of the contract major this package targets.
/// </summary>
public sealed class ReportParseException : ToolException
{
    /// <summary>Creates the exception.</summary>
    /// <param name="message">What went wrong.</param>
    /// <param name="arguments">The exact command that was run.</param>
    /// <param name="exitCode">The process exit code, when it exited.</param>
    /// <param name="standardOutput">Everything the engine wrote to stdout.</param>
    /// <param name="standardError">Everything the engine wrote to stderr.</param>
    public ReportParseException(
        string message,
        IReadOnlyList<string>? arguments = null,
        int? exitCode = null,
        string standardOutput = "",
        string standardError = "")
        : base(message, arguments, exitCode, standardOutput, standardError)
    {
    }
}

/// <summary>
/// The engine did not finish within the requested timeout.
/// </summary>
public sealed class EngineTimeoutException : ToolException
{
    /// <summary>Creates the exception.</summary>
    /// <param name="message">What went wrong.</param>
    /// <param name="arguments">The exact command that was run.</param>
    /// <param name="standardOutput">Everything the engine wrote to stdout.</param>
    /// <param name="standardError">Everything the engine wrote to stderr.</param>
    public EngineTimeoutException(
        string message,
        IReadOnlyList<string>? arguments = null,
        string standardOutput = "",
        string standardError = "")
        : base(message, arguments, null, standardOutput, standardError)
    {
    }
}
