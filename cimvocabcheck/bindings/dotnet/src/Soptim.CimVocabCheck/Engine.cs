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
using System.Runtime.InteropServices;

namespace Soptim.CimVocabCheck;

/// <summary>
/// A way to invoke CIMVocabCheck.
/// </summary>
/// <remarks>
/// Subclasses differ only in how a command line is assembled; everything above this layer sees
/// one type and one report format. This package ships no engine of its own — see
/// <see cref="Discover"/> for how one is found.
/// </remarks>
public abstract class Engine
{
    /// <summary>Executable the engine installs as, looked up on <c>PATH</c>.</summary>
    public const string BinaryName = "cimvocabcheck";

    /// <summary>Image used by the Docker fallback.</summary>
    public const string DefaultImage = "ghcr.io/soptim/cimvocabcheck-cli:latest";

    /// <summary>Where the Docker fallback mounts the working directory.</summary>
    public const string ContainerWorkdir = "/work";

    /// <summary>
    /// Where the Docker fallback mounts, read-only, the directory of a config file that lies above
    /// the working directory and would otherwise be invisible to the engine.
    /// </summary>
    public const string ContainerConfigDirectory = "/config";

    /// <summary>Config file names the engine discovers, in the order it prefers them.</summary>
    public static readonly IReadOnlyList<string> ConfigFileNames = ["opencgmes.jsonc", "opencgmes.json"];

    /// <summary>
    /// The locale a local engine is handed when the caller's would have it read file names as ASCII.
    /// </summary>
    public const string Utf8Locale = "C.UTF-8";

    /// <summary>Environment variable naming a fat JAR to run with <c>java -jar</c>.</summary>
    public const string JarVariable = "CIMVOCABCHECK_JAR";

    /// <summary>Environment variable naming an executable engine.</summary>
    public const string BinaryVariable = "CIMVOCABCHECK_BIN";

    /// <summary>Environment variable overriding the Docker fallback's image.</summary>
    public const string ImageVariable = "CIMVOCABCHECK_DOCKER_IMAGE";

    /// <summary>Environment variable that takes the Docker fallback out of the chain.</summary>
    public const string NoDockerVariable = "CIMVOCABCHECK_NO_DOCKER";

    /// <summary>
    /// Builds the command to spawn: the program, then its arguments.
    /// </summary>
    /// <remarks>
    /// Never a shell string — inputs and schema paths contain spaces.
    /// </remarks>
    /// <param name="arguments">The engine's own arguments.</param>
    /// <param name="usesStandardInput">Whether input will be written to the process.</param>
    /// <param name="workingDirectory">Where the process will start.</param>
    /// <returns>The program followed by its arguments.</returns>
    public abstract IReadOnlyList<string> BuildCommand(
        IReadOnlyList<string> arguments,
        bool usesStandardInput,
        string? workingDirectory);

    /// <summary>
    /// Rewrites one path for this engine. Only the Docker engine needs to.
    /// </summary>
    /// <param name="path">The path as the caller gave it.</param>
    /// <param name="workingDirectory">Where the process will start.</param>
    /// <returns>The path as the engine should see it.</returns>
    public virtual string ResolvePath(string path, string? workingDirectory) => path;

    /// <summary>
    /// The locale variable to set when spawning this engine, if its environment needs one.
    /// </summary>
    /// <remarks>
    /// A local engine is a JVM, which encodes file names in the locale's charset: under a C or
    /// POSIX locale (or none, as in many containers and CI images) a non-ASCII input name cannot
    /// even be opened. The variable POSIX gives precedence to — <c>LC_ALL</c>, else
    /// <c>LC_CTYPE</c>, else <c>LANG</c> — decides; when it is C, POSIX or unset the variable to set
    /// to <see cref="Utf8Locale"/> is returned (<c>LC_ALL</c> when that is what is in force, so a
    /// deliberate <c>LC_ALL=C</c> keeps everything but its charset). Windows takes no locale from
    /// the environment.
    /// </remarks>
    /// <param name="environment">The environment the engine will be spawned with.</param>
    /// <returns>The variable and its value, or <c>null</c> when the locale is usable as it is.</returns>
    public virtual (string Variable, string Value)? LocaleOverride(
        IDictionary<string, string?> environment)
    {
        if (OperatingSystem.IsWindows())
        {
            return null;
        }

        string? NonEmpty(string name) =>
            environment.TryGetValue(name, out var value) && !string.IsNullOrEmpty(value) ? value : null;

        var all = NonEmpty("LC_ALL");
        var effective = all ?? NonEmpty("LC_CTYPE") ?? NonEmpty("LANG") ?? "";
        if (effective is not ("" or "C" or "POSIX"))
        {
            return null;
        }

        return (all is not null ? "LC_ALL" : "LC_CTYPE", Utf8Locale);
    }

    /// <summary>
    /// Cleans up after the process spawned from <paramref name="command"/> was killed before it
    /// finished.
    /// </summary>
    /// <remarks>
    /// Only the Docker engine leaves anything behind: killing <c>docker run</c> stops the client,
    /// not the container. Best effort — the failure that caused the kill matters more.
    /// </remarks>
    /// <param name="command">The command that was killed, as <see cref="BuildCommand"/> built it.</param>
    /// <param name="environment">The environment it ran with, or <c>null</c> for this process's.</param>
    public virtual void Abandon(
        IReadOnlyList<string> command,
        IReadOnlyDictionary<string, string>? environment)
    {
    }

    /// <summary>A one-line description, for diagnostics and error messages.</summary>
    /// <returns>What this engine will run.</returns>
    public abstract string Describe();

    /// <inheritdoc/>
    public override string ToString() => Describe();

    /// <summary>
    /// Returns the engine to run, searching in the documented order.
    /// </summary>
    /// <remarks>
    /// The order is deliberate: an explicit choice beats the environment, the environment beats
    /// whatever happens to be installed, and Docker is the last resort because it is the slowest
    /// and the only one that can reach the network to fetch itself.
    /// </remarks>
    /// <param name="options">Where to look. Defaults to this process's environment.</param>
    /// <returns>The engine to run.</returns>
    /// <exception cref="EngineNotFoundException">Nothing was found.</exception>
    public static Engine Discover(EngineDiscovery? options = null)
    {
        options ??= new EngineDiscovery();
        var searched = new List<string>();

        if (options.EnginePath is { Length: > 0 } explicitPath)
        {
            return ForPath(explicitPath, "the engine passed to this call", options);
        }

        foreach (var variable in new[] { JarVariable, BinaryVariable })
        {
            var value = options.GetEnvironmentVariable(variable);
            searched.Add($"${variable} ({(string.IsNullOrEmpty(value) ? "unset" : value)})");
            if (!string.IsNullOrEmpty(value))
            {
                return ForPath(value, $"${variable}", options);
            }
        }

        searched.Add($"'{BinaryName}' on PATH");
        var onPath = Which(BinaryName, options);
        if (onPath is not null)
        {
            return new BinaryEngine(onPath);
        }

        var image = options.GetEnvironmentVariable(ImageVariable) is { Length: > 0 } configured
            ? configured
            : DefaultImage;
        var disabled = options.NoDocker || Truthy(options.GetEnvironmentVariable(NoDockerVariable));
        searched.Add(
            $"the Docker image {image} ({(disabled ? "disabled" : "requires 'docker' on PATH")})");
        if (!disabled && Which("docker", options) is { } docker)
        {
            return new DockerEngine(image, docker, options.WorkingDirectory ?? ".");
        }

        throw new EngineNotFoundException(
            "no CIMVocabCheck engine found. Install the CLI and put it on PATH, point "
            + $"{JarVariable} at a fat JAR, or make Docker available for the container fallback.",
            searched);
    }

    /// <summary>
    /// Locates a Java launcher for the JAR engines: <c>JAVA_HOME</c> first, then <c>PATH</c>.
    /// </summary>
    /// <param name="options">Where to look.</param>
    /// <returns>The Java launcher.</returns>
    /// <exception cref="EngineNotFoundException">There is no Java on this machine.</exception>
    internal static string FindJava(EngineDiscovery options)
    {
        var executable = RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? "java.exe" : "java";
        if (options.GetEnvironmentVariable("JAVA_HOME") is { Length: > 0 } home)
        {
            var candidate = Path.Combine(home, "bin", executable);
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return Which("java", options)
            ?? throw new EngineNotFoundException(
                "a CIMVocabCheck JAR was selected but no Java launcher was found. The JAR engine "
                + "needs a JRE 21 or newer; install one, or set JAVA_HOME.");
    }

    private static Engine ForPath(string path, string origin, EngineDiscovery options)
    {
        if (!File.Exists(path) && !Directory.Exists(path))
        {
            throw new EngineNotFoundException($"{origin} points at {path}, which does not exist.");
        }

        // Checked against this process's directory, so pinned to it: the engine may run in another.
        path = System.IO.Path.GetFullPath(path);

        return Path.GetExtension(path).Equals(".jar", StringComparison.OrdinalIgnoreCase)
            ? new JarEngine(path, FindJava(options))
            : new BinaryEngine(path);
    }

    private static string? Which(string name, EngineDiscovery options)
    {
        var pathVariable = options.GetEnvironmentVariable("PATH") ?? string.Empty;
        var extensions = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? new[] { ".exe", ".cmd", ".bat", string.Empty }
            : [string.Empty];

        foreach (var directory in pathVariable.Split(Path.PathSeparator))
        {
            if (directory.Length == 0)
            {
                continue;
            }

            foreach (var extension in extensions)
            {
                var candidate = Path.Combine(directory, name + extension);
                if (File.Exists(candidate))
                {
                    return candidate;
                }
            }
        }

        return null;
    }

    private static bool Truthy(string? value) =>
        value is not null
        && value.Trim().ToLowerInvariant() is not ("" or "0" or "false" or "no");
}

/// <summary>
/// Where <see cref="Engine.Discover"/> should look.
/// </summary>
public sealed record EngineDiscovery
{
    /// <summary>An explicit engine path — a <c>.jar</c> is run with Java, anything else is run.</summary>
    public string? EnginePath { get; init; }

    /// <summary>The environment to read, instead of this process's own.</summary>
    public IReadOnlyDictionary<string, string>? Environment { get; init; }

    /// <summary>The working directory a Docker engine should mount.</summary>
    public string? WorkingDirectory { get; init; }

    /// <summary>Set to take the Docker fallback out of the chain.</summary>
    public bool NoDocker { get; init; }

    /// <summary>Reads one variable from <see cref="Environment"/>, else from the process.</summary>
    /// <param name="name">The variable to read.</param>
    /// <returns>Its value, or <c>null</c>.</returns>
    public string? GetEnvironmentVariable(string name) =>
        Environment is null
            ? System.Environment.GetEnvironmentVariable(name)
            : Environment.GetValueOrDefault(name);
}

/// <summary>A fat JAR run with <c>java -jar</c>.</summary>
/// <param name="jar">The JAR to run.</param>
/// <param name="java">The Java launcher to run it with.</param>
public sealed class JarEngine(string jar, string java) : Engine
{
    /// <summary>The JAR this engine runs.</summary>
    public string Jar { get; } = jar;

    /// <summary>The Java launcher this engine runs it with.</summary>
    public string Java { get; } = java;

    /// <inheritdoc/>
    public override IReadOnlyList<string> BuildCommand(
        IReadOnlyList<string> arguments,
        bool usesStandardInput,
        string? workingDirectory) => [Java, "-jar", Jar, .. arguments];

    /// <inheritdoc/>
    public override string Describe() => $"{Java} -jar {Jar}";
}

/// <summary>An executable engine — the CLI's launcher script, or a native binary.</summary>
/// <param name="path">The executable.</param>
public sealed class BinaryEngine(string path) : Engine
{
    /// <summary>The executable this engine runs.</summary>
    public string Path { get; } = path;

    /// <inheritdoc/>
    public override IReadOnlyList<string> BuildCommand(
        IReadOnlyList<string> arguments,
        bool usesStandardInput,
        string? workingDirectory) => [Path, .. arguments];

    /// <inheritdoc/>
    public override string Describe() => Path;
}

/// <summary>
/// The published container image, with the working directory mounted at its <c>WORKDIR</c>.
/// </summary>
/// <remarks>
/// Inputs are therefore addressed relative to the working directory. That is what the report
/// contract asks for anyway: the paths in a report are the paths that were passed in, and CI
/// annotations only line up when they are repository-relative.
/// </remarks>
/// <param name="image">The image to run.</param>
/// <param name="docker">The <c>docker</c> executable.</param>
/// <param name="workingDirectory">The directory to mount when a call does not name one.</param>
public sealed class DockerEngine(string image, string docker, string workingDirectory) : Engine
{
    /// <summary>The image this engine runs.</summary>
    public string Image { get; } = image;

    /// <summary>The <c>docker</c> executable.</summary>
    public string Docker { get; } = docker;

    /// <summary>The directory mounted when a call does not name one of its own.</summary>
    public string WorkingDirectory { get; } = workingDirectory;

    /// <inheritdoc/>
    public override IReadOnlyList<string> BuildCommand(
        IReadOnlyList<string> arguments,
        bool usesStandardInput,
        string? workingDirectory)
    {
        // Named, so a run that has to be killed can be found and removed again.
        var command = new List<string> { Docker, "run", "--rm", "--name", $"cimvocabcheck-{Guid.NewGuid():N}" };
        if (usesStandardInput)
        {
            command.Add("-i");
        }

        var mount = Mount(workingDirectory);
        command.Add("-v");
        command.Add($"{mount}:{ContainerWorkdir}");
        var config = NamesSchemaSource(arguments) ? null : ConfigAbove(mount);
        if (config is not null)
        {
            // The engine discovers its config upward from the working directory, which inside the
            // container stops at the mount: a config above it would be silently missed, leaving a
            // syntax-only check that reports every query as valid.
            command.Add("-v");
            command.Add($"{System.IO.Path.GetDirectoryName(config)}:{ContainerConfigDirectory}:ro");
        }

        command.Add("-w");
        command.Add(ContainerWorkdir);
        command.Add(Image);
        if (config is not null)
        {
            command.Add("--config");
            command.Add($"{ContainerConfigDirectory}/{System.IO.Path.GetFileName(config)}");
        }

        command.AddRange(arguments);
        return command;
    }

    /// <inheritdoc/>
    public override string ResolvePath(string path, string? workingDirectory)
    {
        if (path == "-")
        {
            return path;
        }

        var mount = Mount(workingDirectory);
        var absolute = System.IO.Path.GetFullPath(
            System.IO.Path.IsPathRooted(path) ? path : System.IO.Path.Combine(mount, path));

        // The mount and an absolute input may each be spelled through a symlink, or not; the input
        // is inside when either spelling of it is.
        var realMount = RealPath(mount);
        var spellings = new[] { (absolute, mount), (absolute, realMount), (RealPath(absolute), realMount) };
        foreach (var (inside, root) in spellings)
        {
            if (RelativeTo(inside, root) is { } relative)
            {
                return relative;
            }
        }

        throw new CimVocabCheckException(
            $"'{path}' is outside the working directory {mount}, which is the only path the "
            + "Docker engine mounts. Pass paths relative to the working directory, run from a "
            + $"directory that contains them, or configure a local engine ({JarVariable} / "
            + $"{BinaryVariable}).");
    }

    /// <summary>
    /// <paramref name="path"/> relative to <paramref name="root"/> with <c>/</c> separators, or
    /// <c>null</c> when it is not inside it. Both must be full paths.
    /// </summary>
    private static string? RelativeTo(string path, string root)
    {
        var comparison = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (System.IO.Path.TrimEndingDirectorySeparator(path)
            .Equals(System.IO.Path.TrimEndingDirectorySeparator(root), comparison))
        {
            return ".";
        }

        var prefix = root.EndsWith(System.IO.Path.DirectorySeparatorChar)
            ? root
            : root + System.IO.Path.DirectorySeparatorChar;
        return path.StartsWith(prefix, comparison)
            ? path[prefix.Length..].Replace(System.IO.Path.DirectorySeparatorChar, '/')
            : null;
    }

    /// <summary>
    /// <paramref name="path"/> with every symlink along it resolved, as far as it exists; the part
    /// that does not exist yet is kept as written.
    /// </summary>
    private static string RealPath(string path, int depth = 0)
    {
        // A link cycle would otherwise recurse forever; the kernel gives up at 40 too.
        if (depth > 40)
        {
            return path;
        }

        var full = System.IO.Path.GetFullPath(path);
        var current = System.IO.Path.GetPathRoot(full) ?? string.Empty;
        var parts = full[current.Length..].Split(
            System.IO.Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries);
        for (var at = 0; at < parts.Length; at++)
        {
            var next = System.IO.Path.Combine(current, parts[at]);
            FileSystemInfo info = Directory.Exists(next) ? new DirectoryInfo(next) : new FileInfo(next);
            if (!info.Exists)
            {
                return System.IO.Path.Combine([current, .. parts[at..]]);
            }

            if (info.LinkTarget is not null && info.ResolveLinkTarget(returnFinalTarget: true) is { } target)
            {
                // The target may itself sit under a symlinked directory.
                next = RealPath(target.FullName, depth + 1);
            }

            current = next;
        }

        return current;
    }

    /// <inheritdoc/>
    /// <remarks>The engine runs inside the image, under the image's locale.</remarks>
    public override (string Variable, string Value)? LocaleOverride(
        IDictionary<string, string?> environment) => null;

    /// <inheritdoc/>
    public override void Abandon(
        IReadOnlyList<string> command,
        IReadOnlyDictionary<string, string>? environment)
    {
        var at = command.ToList().IndexOf("--name");
        if (at < 0 || at + 1 >= command.Count)
        {
            return;
        }

        var startInfo = new ProcessStartInfo(Docker)
        {
            ArgumentList = { "rm", "--force", command[at + 1] },
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        if (environment is not null)
        {
            startInfo.Environment.Clear();
            foreach (var (key, value) in environment)
            {
                startInfo.Environment[key] = value;
            }
        }

        try
        {
            using var process = Process.Start(startInfo);
            process?.WaitForExit(TimeSpan.FromSeconds(30));
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or IOException)
        {
            // The container outlives us; the timeout being reported matters more.
        }
    }

    /// <inheritdoc/>
    public override string Describe() => $"{Docker} run {Image} (mounting {WorkingDirectory})";

    /// <summary>
    /// The directory to mount: the one the call runs in, else this engine's own. An engine is
    /// discovered once and may then be used from several working directories, so the mount
    /// cannot be frozen at discovery time.
    /// </summary>
    private string Mount(string? workingDirectory) =>
        System.IO.Path.GetFullPath(workingDirectory ?? WorkingDirectory);

    /// <summary>Flags that name a schema source; when a call passes one, no config is discovered.</summary>
    private static readonly string[] SchemaSourceFlags =
        ["-c", "--config", "-s", "--schema", "-e", "--endpoint"];

    /// <summary>
    /// The config file the engine would discover from <paramref name="directory"/>, if it lies
    /// above it; <c>null</c> when the nearest config is inside the directory (the engine finds that
    /// one itself) or when there is none at all.
    /// </summary>
    private static string? ConfigAbove(string directory)
    {
        for (var candidate = new DirectoryInfo(directory); candidate is not null; candidate = candidate.Parent)
        {
            foreach (var name in ConfigFileNames)
            {
                var file = System.IO.Path.Combine(candidate.FullName, name);
                if (File.Exists(file))
                {
                    return candidate.FullName == new DirectoryInfo(directory).FullName ? null : file;
                }
            }
        }

        return null;
    }

    /// <summary>Whether <paramref name="arguments"/> choose a schema source themselves.</summary>
    private static bool NamesSchemaSource(IReadOnlyList<string> arguments)
    {
        foreach (var argument in arguments)
        {
            if (argument == "--")
            {
                return false;
            }

            var flag = argument.StartsWith("--", StringComparison.Ordinal)
                ? argument.Split('=', 2)[0]
                : argument.Length >= 2 ? argument[..2] : argument;
            if (SchemaSourceFlags.Contains(flag, StringComparer.Ordinal))
            {
                return true;
            }
        }

        return false;
    }
}
