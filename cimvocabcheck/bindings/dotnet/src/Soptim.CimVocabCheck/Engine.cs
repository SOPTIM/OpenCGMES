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
        var command = new List<string> { Docker, "run", "--rm" };
        if (usesStandardInput)
        {
            command.Add("-i");
        }

        command.Add("-v");
        command.Add($"{Mount(workingDirectory)}:{ContainerWorkdir}");
        command.Add("-w");
        command.Add(ContainerWorkdir);
        command.Add(Image);
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

        var prefix = mount.EndsWith(System.IO.Path.DirectorySeparatorChar)
            ? mount
            : mount + System.IO.Path.DirectorySeparatorChar;
        if (!absolute.StartsWith(prefix, StringComparison.Ordinal))
        {
            throw new CimVocabCheckException(
                $"'{path}' is outside the working directory {mount}, which is the only path the "
                + "Docker engine mounts. Pass paths relative to the working directory, run from a "
                + $"directory that contains them, or configure a local engine ({JarVariable} / "
                + $"{BinaryVariable}).");
        }

        return absolute[prefix.Length..].Replace(System.IO.Path.DirectorySeparatorChar, '/');
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
}
