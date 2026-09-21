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

using System.Runtime.InteropServices;

namespace Soptim.CimVocabCheck.Tests;

/// <summary>Shared fixtures and scaffolding.</summary>
internal static class TestSupport
{
    /// <summary>A query with one unused variable: parses, WARN findings but no error.</summary>
    public const string WarningQuery = "SELECT ?s WHERE { ?s ?p ?o }";

    /// <summary>Fails to parse: exactly one ERROR.</summary>
    public const string BrokenQuery = "SELEEECT * WHERE { ?s ?p ?o }";

    /// <summary>Clean in syntax-only mode.</summary>
    public const string CleanQuery = "SELECT * WHERE { ?s ?p ?o }";

    /// <summary>Set in CI so a missing engine fails the run instead of quietly skipping it.</summary>
    public const string RequireEngine = "CIMVOCABCHECK_TESTS_REQUIRE_ENGINE";

    /// <summary>A minimal well-formed report document, for tests that must not spawn anything.</summary>
    public static string ReportJson(string results = "") =>
        $$"""
          {
            "contractVersion": "1.0",
            "tool": { "name": "cimvocabcheck", "version": "1.2.3" },
            "summary": { "files": 0, "valid": 0, "invalid": 0,
                         "errors": 0, "warnings": 0, "infos": 0 },
            "results": [{{results}}]
          }
          """;

    /// <summary>Whether this platform can run the shell-script stand-ins for an engine.</summary>
    public static bool CanScript => !RuntimeInformation.IsOSPlatform(OSPlatform.Windows);
}

/// <summary>A directory that deletes itself.</summary>
internal sealed class TempDir : IDisposable
{
    public TempDir()
    {
        Path = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            $"cimvocabcheck-test-{Environment.ProcessId}-{Guid.NewGuid():N}");
        Directory.CreateDirectory(Path);
        // Resolve /var -> /private/var and friends, so a mount comparison lines up.
        Path = new DirectoryInfo(Path).ResolveLinkTarget(returnFinalTarget: true)?.FullName ?? Path;
    }

    /// <summary>Where the directory is.</summary>
    public string Path { get; }

    /// <summary>Writes an input and returns its name, relative.</summary>
    public string Write(string name, string text)
    {
        var target = System.IO.Path.Combine(Path, name);
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target)!);
        File.WriteAllText(target, text);
        return name;
    }

    /// <summary>A project directory whose config names no schema: syntax-only validation.</summary>
    public static TempDir Workspace()
    {
        var directory = new TempDir();
        directory.Write("opencgmes.jsonc", """{"cimvocabcheck": {}}""");
        return directory;
    }

    /// <summary>Writes an executable that behaves like an engine, so a test needs no JVM.</summary>
    /// <remarks>
    /// POSIX only: a shell-script stand-in is not a thing Windows can execute. Callers skip via
    /// <see cref="TestSupport.CanScript"/>; the throw is what the platform analyzer reads as the
    /// guard for <see cref="File.SetUnixFileMode(string, UnixFileMode)"/>.
    /// </remarks>
    public string Script(string name, string body)
    {
        if (OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("the script fixtures need a POSIX shell");
        }

        var path = System.IO.Path.Combine(Path, name);
        File.WriteAllText(path, $"#!/bin/sh\n{body}\n");
        File.SetUnixFileMode(
            path,
            UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute
            | UnixFileMode.GroupRead | UnixFileMode.GroupExecute
            | UnixFileMode.OtherRead | UnixFileMode.OtherExecute);
        return path;
    }

    /// <summary>An engine that ignores its arguments and returns what the test told it to.</summary>
    public Engine Scripted(string name, string stdout, string stderr = "", int exitCode = 0)
    {
        var quoted = "'" + stderr.Replace("'", "'\\''", StringComparison.Ordinal) + "'";
        var body = $"cat <<'REPORT'\n{stdout}\nREPORT\nprintf '%s' {quoted} >&2\nexit {exitCode}";
        return new BinaryEngine(Script(name, body));
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(Path, recursive: true);
        }
        catch (IOException)
        {
            // A test left something locked; the temp directory is the OS's problem now.
        }
    }
}
