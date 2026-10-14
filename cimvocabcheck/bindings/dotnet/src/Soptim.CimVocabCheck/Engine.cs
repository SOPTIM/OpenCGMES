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
using System.Text.Json;

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
    /// Where the Docker fallback mounts, read-only, what it has to reach outside the working
    /// directory: a config, the schema paths it names, a schema passed in. Each goes to this
    /// directory followed by its absolute host path (see <see cref="DockerEngine.ContainerPath"/>),
    /// so a config's relative paths lead to the same places in the container as on the host.
    /// </summary>
    public const string ContainerHostDirectory = "/host";

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
    /// <param name="mounts">
    /// Paths to mount read-only, as <see cref="ResolveConfig"/> and <see cref="ResolveSchema"/>
    /// collected them. Only the Docker engine has any.
    /// </param>
    /// <returns>The program followed by its arguments.</returns>
    public abstract IReadOnlyList<string> BuildCommand(
        IReadOnlyList<string> arguments,
        bool usesStandardInput,
        string? workingDirectory,
        IReadOnlyList<VolumeMount>? mounts = null);

    /// <summary>
    /// Rewrites one path for this engine. Only the Docker engine needs to.
    /// </summary>
    /// <param name="path">The path as the caller gave it.</param>
    /// <param name="workingDirectory">Where the process will start.</param>
    /// <returns>The path as the engine should see it.</returns>
    public virtual string ResolvePath(string path, string? workingDirectory) => path;

    /// <summary>
    /// Rewrites a config file path. The Docker engine mounts one outside the working directory
    /// instead of refusing it.
    /// </summary>
    /// <param name="path">The path as the caller gave it.</param>
    /// <param name="workingDirectory">Where the process will start.</param>
    /// <param name="mounts">Collects what <see cref="BuildCommand"/> has to mount.</param>
    /// <returns>The path as the engine should see it.</returns>
    public virtual string ResolveConfig(
        string path, string? workingDirectory, IList<VolumeMount> mounts) =>
        ResolvePath(path, workingDirectory);

    /// <summary>
    /// Rewrites a schema file or directory path. The Docker engine mounts one outside the
    /// working directory instead of refusing it.
    /// </summary>
    /// <param name="path">The path as the caller gave it.</param>
    /// <param name="workingDirectory">Where the process will start.</param>
    /// <param name="mounts">Collects what <see cref="BuildCommand"/> has to mount.</param>
    /// <returns>The path as the engine should see it.</returns>
    public virtual string ResolveSchema(
        string path, string? workingDirectory, IList<VolumeMount> mounts) =>
        ResolvePath(path, workingDirectory);

    /// <summary>
    /// The locale variable to set when spawning this engine, if its environment needs one.
    /// </summary>
    /// <remarks>
    /// A local engine is a JVM, which encodes file names in the locale's charset: under a C or
    /// POSIX locale (or none, as in many containers and CI images) a non-ASCII input name cannot
    /// even be opened. Two cases need a variable set to <see cref="Utf8Locale"/>. When the variable
    /// POSIX gives precedence to — <c>LC_ALL</c>, else <c>LC_CTYPE</c>, else <c>LANG</c> — is C,
    /// POSIX or unset, that one (<c>LC_ALL</c> when that is what is in force, so a deliberate
    /// <c>LC_ALL=C</c> keeps everything but its charset, else <c>LC_CTYPE</c>). When a locale the
    /// environment names is not installed, the C library falls back to C for <em>every</em>
    /// category, so then <c>LC_ALL</c>. Windows takes no locale from the environment.
    /// </remarks>
    /// <param name="environment">The environment the engine will be spawned with.</param>
    /// <returns>The variable and its value, or <c>null</c> when the locale is usable as it is.</returns>
    public (string Variable, string Value)? LocaleOverride(IDictionary<string, string?> environment) =>
        LocaleOverride(environment, InstalledLocales());

    /// <summary>
    /// <see cref="LocaleOverride(IDictionary{string, string?})"/>, given the locales that are
    /// installed.
    /// </summary>
    /// <param name="environment">The environment the engine will be spawned with.</param>
    /// <param name="installedLocales">
    /// What <c>locale -a</c> lists, as <see cref="InstalledLocales"/> returns it; <c>null</c> when
    /// that is not known, in which case a named locale is trusted.
    /// </param>
    /// <returns>The variable and its value, or <c>null</c> when the locale is usable as it is.</returns>
    public virtual (string Variable, string Value)? LocaleOverride(
        IDictionary<string, string?> environment, IReadOnlySet<string>? installedLocales)
    {
        if (OperatingSystem.IsWindows())
        {
            return null;
        }

        string? NonEmpty(string name) =>
            environment.TryGetValue(name, out var value) && !string.IsNullOrEmpty(value) ? value : null;

        var all = NonEmpty("LC_ALL");
        IEnumerable<string> named = all is not null
            ? [all]
            : environment
                .Where(pair => (pair.Key == "LANG" || pair.Key.StartsWith("LC_", StringComparison.Ordinal))
                    && !string.IsNullOrEmpty(pair.Value))
                .Select(pair => pair.Value!);
        if (installedLocales is not null && named.Any(name => !IsInstalled(name, installedLocales)))
        {
            return ("LC_ALL", Utf8Locale);
        }

        var effective = all ?? NonEmpty("LC_CTYPE") ?? NonEmpty("LANG") ?? "";
        if (effective is not ("" or "C" or "POSIX"))
        {
            return null;
        }

        return (all is not null ? "LC_ALL" : "LC_CTYPE", Utf8Locale);
    }

    /// <summary>
    /// The locales <c>locale -a</c> lists, or <c>null</c> when that cannot be found out.
    /// </summary>
    /// <remarks>
    /// Asked once per process. Without a <c>locale</c> command (musl-based images have none, and
    /// musl reads every locale as UTF-8 anyway) nothing is known.
    /// </remarks>
    /// <returns>The installed locales, or <c>null</c>.</returns>
    public static IReadOnlySet<string>? InstalledLocales() => InstalledLocalesOnce.Value;

    private static readonly Lazy<IReadOnlySet<string>?> InstalledLocalesOnce = new(ListInstalledLocales);

    /// <summary>Locales the C library always has, whatever is installed.</summary>
    private static readonly string[] BuiltinLocales = ["C", "POSIX", "C.utf8"];

    private static HashSet<string>? ListInstalledLocales()
    {
        if (OperatingSystem.IsWindows() || Which("locale", new EngineDiscovery()) is not { } locale)
        {
            return null;
        }

        try
        {
            var startInfo = new ProcessStartInfo(locale)
            {
                ArgumentList = { "-a" },
                RedirectStandardOutput = true,
                UseShellExecute = false,
            };
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                return null;
            }

            var listed = process.StandardOutput.ReadToEnd();
            if (!process.WaitForExit(10_000) || process.ExitCode != 0)
            {
                return null;
            }

            return listed
                .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .ToHashSet(StringComparer.Ordinal);
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or IOException
            or InvalidOperationException)
        {
            return null;
        }
    }

    /// <summary>Whether <paramref name="name"/> is one of <paramref name="installed"/>, however
    /// the codeset is spelled.</summary>
    private static bool IsInstalled(string name, IReadOnlySet<string> installed)
    {
        var wanted = LocaleKey(name);
        return BuiltinLocales.Concat(installed).Any(entry => LocaleKey(entry) == wanted);
    }

    /// <summary><c>en_US.UTF-8</c> and <c>en_US.utf8</c> name one locale; this is what they have
    /// in common.</summary>
    private static string LocaleKey(string name)
    {
        var dot = name.IndexOf('.', StringComparison.Ordinal);
        if (dot < 0)
        {
            return name;
        }

        var rest = name[(dot + 1)..];
        var at = rest.IndexOf('@', StringComparison.Ordinal);
        var codeset = at < 0 ? rest : rest[..at];
        var modifier = at < 0 ? "" : rest[at..];
        var key = new string(codeset.Where(c => c is not ('-' or '_')).Select(char.ToLowerInvariant).ToArray());
        return $"{name[..dot]}.{key}{modifier}";
    }

    /// <summary>
    /// Cleans up after the process spawned from <paramref name="command"/> was killed before it
    /// finished.
    /// </summary>
    /// <remarks>
    /// Only the Docker engine leaves anything behind: killing <c>docker run</c> stops the client,
    /// not the container. Best effort — the failure that caused the kill matters more, so this
    /// never throws, and it gives up after <see cref="AbandonLimit"/> or when
    /// <paramref name="cancellationToken"/> fires: a Docker daemon that stopped responding is a
    /// likely reason the run was killed in the first place.
    /// </remarks>
    /// <param name="command">The command that was killed, as <see cref="BuildCommand"/> built it.</param>
    /// <param name="environment">The environment it ran with, or <c>null</c> for this process's.</param>
    /// <param name="cancellationToken">Stops waiting for the cleanup.</param>
    /// <returns>A task that completes once the cleanup finished or was given up on.</returns>
    public virtual Task AbandonAsync(
        IReadOnlyList<string> command,
        IReadOnlyDictionary<string, string>? environment,
        CancellationToken cancellationToken = default) => Task.CompletedTask;

    /// <summary>How long <see cref="AbandonAsync"/> waits for its cleanup at most.</summary>
    public static readonly TimeSpan AbandonLimit = TimeSpan.FromSeconds(30);

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
        string? workingDirectory,
        IReadOnlyList<VolumeMount>? mounts = null) => [Java, "-jar", Jar, .. arguments];

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
        string? workingDirectory,
        IReadOnlyList<VolumeMount>? mounts = null) => [Path, .. arguments];

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
        string? workingDirectory,
        IReadOnlyList<VolumeMount>? mounts = null)
    {
        // Named, so a run that has to be killed can be found and removed again.
        var command = new List<string> { Docker, "run", "--rm", "--name", $"cimvocabcheck-{Guid.NewGuid():N}" };
        if (usesStandardInput)
        {
            command.Add("-i");
        }

        var workdir = Mount(workingDirectory);
        command.Add("-v");
        command.Add($"{workdir}:{ContainerWorkdir}");
        var volumes = new List<VolumeMount>(mounts ?? []);
        string? configFlag = null;
        if (!NamesSchemaSource(arguments) && NearestConfig(workdir) is { } config)
        {
            string seen;
            if (RelativeTo(System.IO.Path.GetDirectoryName(config)!, workdir) == ".")
            {
                seen = $"{ContainerWorkdir}/{System.IO.Path.GetFileName(config)}";
            }
            else
            {
                // The engine discovers its config upward from the working directory, which inside
                // the container stops at the mount: a config above it would be silently missed,
                // leaving a syntax-only check that reports every query as valid.
                seen = MountConfig(config, workdir, volumes);
                configFlag = seen;
            }

            MountConfigPaths(config, seen, workdir, volumes);
        }

        foreach (var volume in volumes)
        {
            command.Add("-v");
            command.Add($"{volume.Host}:{volume.Container}:ro");
        }

        command.Add("-w");
        command.Add(ContainerWorkdir);
        command.Add(Image);
        if (configFlag is not null)
        {
            command.Add("--config");
            command.Add(configFlag);
        }

        command.AddRange(arguments);
        return command;
    }

    /// <inheritdoc/>
    public override string ResolvePath(string path, string? workingDirectory) =>
        TryResolvePath(path, workingDirectory)
        ?? throw new CimVocabCheckException(
            $"'{path}' is outside the working directory {Mount(workingDirectory)}, which is the "
            + "only path the Docker engine mounts. Pass paths relative to the working directory, "
            + "run from a directory that contains them, or configure a local engine "
            + $"({JarVariable} / {BinaryVariable}).");

    /// <inheritdoc/>
    /// <remarks>
    /// A config outside the working directory has its directory mounted read-only under
    /// <see cref="Engine.ContainerHostDirectory"/>. Either way, each schema path the config names
    /// that the container could not otherwise reach is mounted too, so the config's own relative
    /// paths resolve as they do on the host — exactly as for a config the engine discovers.
    /// </remarks>
    /// <exception cref="CimVocabCheckException">The config names a path the container cannot have.</exception>
    public override string ResolveConfig(
        string path, string? workingDirectory, IList<VolumeMount> mounts)
    {
        var workdir = Mount(workingDirectory);
        if (Outside(path, workingDirectory) is not { } host)
        {
            var resolved = ResolvePath(path, workingDirectory);
            var inside = PosixNormalize(PosixJoin(ContainerWorkdir, resolved));
            MountConfigPaths(System.IO.Path.GetFullPath(path, workdir), inside, workdir, mounts);
            return resolved;
        }

        var seen = MountConfig(host, workdir, mounts);
        MountConfigPaths(host, seen, workdir, mounts);
        return seen;
    }

    /// <inheritdoc/>
    /// <remarks>
    /// A schema outside the working directory is mounted read-only at its
    /// <see cref="ContainerPath"/>.
    /// </remarks>
    public override string ResolveSchema(
        string path, string? workingDirectory, IList<VolumeMount> mounts)
    {
        if (Outside(path, workingDirectory) is not { } host)
        {
            return ResolvePath(path, workingDirectory);
        }

        var container = ContainerPath(host);
        AddMount(host, container, Mount(workingDirectory), mounts, null);
        return container;
    }

    /// <summary>
    /// Where the Docker fallback mounts a host path: under <see cref="Engine.ContainerHostDirectory"/>,
    /// mirroring its absolute path, so that <c>..</c> leads to the same place on both sides. A
    /// Windows drive becomes a directory of its own: <c>C:\repo</c> is <c>/host/C/repo</c>.
    /// </summary>
    /// <param name="hostPath">The path on the host.</param>
    /// <returns>Where the engine sees it inside the container.</returns>
    public static string ContainerPath(string hostPath)
    {
        var full = System.IO.Path.GetFullPath(hostPath);
        var root = System.IO.Path.GetPathRoot(full) ?? string.Empty;
        var segments = new List<string>();
        var drive = root.TrimEnd('\\', '/').TrimEnd(':');
        if (drive.Length > 0)
        {
            segments.Add(drive);
        }

        segments.AddRange(full[root.Length..].Split(
            [System.IO.Path.DirectorySeparatorChar, System.IO.Path.AltDirectorySeparatorChar],
            StringSplitOptions.RemoveEmptyEntries));
        return segments.Count == 0 ? ContainerHostDirectory : $"{ContainerHostDirectory}/{string.Join('/', segments)}";
    }

    /// <summary>Container paths a mount must neither be nor contain: the image's own directories
    /// and the mounts this class makes.</summary>
    private static readonly string[] ImagePaths =
    [
        ContainerWorkdir, ContainerHostDirectory, "/opt/cimvocabcheck", "/usr", "/bin", "/sbin", "/lib",
        "/lib64", "/etc", "/tmp", "/var", "/proc", "/sys", "/dev",
    ];

    /// <summary>Container directories nothing may be mounted inside: other mounts, and the
    /// kernel's.</summary>
    private static readonly string[] NotInside = [ContainerWorkdir, ContainerHostDirectory, "/proc", "/sys", "/dev"];

    /// <summary>
    /// Mounts the directory of <paramref name="config"/>, which is outside the working directory;
    /// returns where the engine sees the config.
    /// </summary>
    private static string MountConfig(string config, string workdir, IList<VolumeMount> mounts)
    {
        var directory = System.IO.Path.GetDirectoryName(config)!;
        var container = ContainerPath(directory);
        AddMount(directory, container, workdir, mounts, null);
        return $"{container}/{System.IO.Path.GetFileName(config)}";
    }

    /// <summary>
    /// Mounts each schema path <paramref name="config"/> names that the engine, reading the config
    /// at <paramref name="seen"/>, would otherwise look for in a place the container does not have.
    /// A path that does not exist is left alone: the engine reports it, and binding it would make
    /// Docker create an empty directory in its place.
    /// </summary>
    private static void MountConfigPaths(string config, string seen, string workdir, IList<VolumeMount> mounts)
    {
        var directory = System.IO.Path.GetDirectoryName(config)!;
        foreach (var path in ConfigSchemaPaths(config))
        {
            var drive = (path.Length >= 2 && path[1] == ':') || path.StartsWith(@"\\", StringComparison.Ordinal);
            if (drive || (OperatingSystem.IsWindows() && System.IO.Path.IsPathRooted(path)))
            {
                throw new CimVocabCheckException(
                    $"{config} names the schema path '{path}', a Windows path the Docker engine cannot "
                    + "read. Make it relative to the config, or configure a local engine "
                    + $"({JarVariable} / {BinaryVariable}).");
            }

            var host = System.IO.Path.TrimEndingDirectorySeparator(System.IO.Path.GetFullPath(path, directory));
            if (!File.Exists(host) && !Directory.Exists(host))
            {
                continue;
            }

            var container = PosixNormalize(PosixJoin(PosixParent(seen), path.Replace('\\', '/')));
            AddMount(host, container, workdir, mounts, $"{config} names the schema path '{path}'");
        }
    }

    /// <summary>
    /// Adds <paramref name="host"/> at <paramref name="container"/> to <paramref name="mounts"/>,
    /// unless a mount already shows it there.
    /// </summary>
    /// <exception cref="CimVocabCheckException">
    /// <paramref name="container"/> is one of the image's own directories, or inside a directory
    /// nothing may be mounted in — except where <see cref="ContainerPath"/> put it.
    /// </exception>
    private static void AddMount(
        string host, string container, string workdir, IList<VolumeMount> mounts, string? origin)
    {
        foreach (var (mounted, target) in mounts.Select(m => (m.Host, m.Container)).Prepend((workdir, ContainerWorkdir)))
        {
            if (RelativeTo(host, mounted) is { } rest
                && PosixNormalize(PosixJoin(target, rest == "." ? "" : rest)) == container)
            {
                return;
            }
        }

        var mirrored = container == ContainerPath(host) && container != ContainerHostDirectory;
        if (!mirrored && !Mountable(container))
        {
            throw new CimVocabCheckException(
                $"{origin ?? $"'{host}'"}, which the Docker engine would see at {container}, a path inside "
                + "its image. Make the path lead somewhere else, pass the schema itself, or configure a "
                + $"local engine ({JarVariable} / {BinaryVariable}).");
        }

        mounts.Add(new VolumeMount(host, container));
    }

    private static bool Mountable(string container) =>
        container.StartsWith('/')
        && container != "/"
        && !ImagePaths.Any(own => own == container || own.StartsWith(container + "/", StringComparison.Ordinal))
        && !NotInside.Any(directory => container.StartsWith(directory + "/", StringComparison.Ordinal));

    /// <summary>The schema paths <paramref name="config"/> names, as written; none when it cannot be
    /// read. A config the engine will reject anyway is the engine's to report.</summary>
    private static List<string> ConfigSchemaPaths(string config)
    {
        try
        {
            using var document = JsonDocument.Parse(
                File.ReadAllText(config),
                new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true });
            if (document.RootElement.ValueKind != JsonValueKind.Object
                || !document.RootElement.TryGetProperty("cimvocabcheck", out var section)
                || section.ValueKind != JsonValueKind.Object)
            {
                return [];
            }

            var paths = new List<string>();
            if (section.TryGetProperty("schemas", out var schemas) && schemas.ValueKind == JsonValueKind.Array)
            {
                paths.AddRange(schemas.EnumerateArray()
                    .Where(item => item.ValueKind == JsonValueKind.String)
                    .Select(item => item.GetString()!));
            }

            if (section.TryGetProperty("schemasDirectory", out var directory)
                && directory.ValueKind == JsonValueKind.String)
            {
                paths.Add(directory.GetString()!);
            }

            return paths;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            return [];
        }
    }

    private static string PosixParent(string path)
    {
        var slash = path.LastIndexOf('/');
        return slash switch
        {
            < 0 => ".",
            0 => "/",
            _ => path[..slash],
        };
    }

    private static string PosixJoin(string basePath, string path) =>
        path.StartsWith('/') ? path : $"{basePath}/{path}";

    /// <summary>Resolves <c>.</c> and <c>..</c> in a container path, lexically.</summary>
    private static string PosixNormalize(string path)
    {
        var segments = new List<string>();
        foreach (var segment in path.Split('/'))
        {
            switch (segment)
            {
                case "" or ".":
                    break;
                case "..":
                    if (segments.Count > 0)
                    {
                        segments.RemoveAt(segments.Count - 1);
                    }

                    break;
                default:
                    segments.Add(segment);
                    break;
            }
        }

        return "/" + string.Join('/', segments);
    }

    /// <summary>
    /// The real host path when <paramref name="path"/> exists but lies outside the mount, else
    /// <c>null</c>. A path that does not exist is left to <see cref="ResolvePath"/> to refuse:
    /// binding it would make Docker create an empty directory in its place.
    /// </summary>
    private string? Outside(string path, string? workingDirectory)
    {
        if (TryResolvePath(path, workingDirectory) is not null)
        {
            return null;
        }

        var absolute = System.IO.Path.GetFullPath(path, Mount(workingDirectory));
        var real = System.IO.Path.TrimEndingDirectorySeparator(RealPath(absolute));
        return File.Exists(real) || Directory.Exists(real) ? real : null;
    }

    private string? TryResolvePath(string path, string? workingDirectory)
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

        return null;
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
        IDictionary<string, string?> environment, IReadOnlySet<string>? installedLocales) => null;

    /// <inheritdoc/>
    public override async Task AbandonAsync(
        IReadOnlyList<string> command,
        IReadOnlyDictionary<string, string>? environment,
        CancellationToken cancellationToken = default)
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

        Process? process;
        try
        {
            process = Process.Start(startInfo);
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or IOException)
        {
            // The container outlives us; the timeout being reported matters more.
            return;
        }

        if (process is null)
        {
            return;
        }

        using (process)
        using (var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
        {
            limit.CancelAfter(AbandonLimit);
            try
            {
                await process.WaitForExitAsync(limit.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                try
                {
                    process.Kill();
                }
                catch (Exception error) when (error is InvalidOperationException or NotSupportedException)
                {
                    // Exited in the meantime.
                }
            }
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

    /// <summary>The config file the engine would discover from <paramref name="directory"/>, or
    /// <c>null</c>.</summary>
    private static string? NearestConfig(string directory)
    {
        for (var candidate = new DirectoryInfo(directory); candidate is not null; candidate = candidate.Parent)
        {
            foreach (var name in ConfigFileNames)
            {
                var file = System.IO.Path.Combine(candidate.FullName, name);
                if (File.Exists(file))
                {
                    return file;
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

/// <summary>A host path the Docker engine bind-mounts read-only for one call.</summary>
/// <param name="Host">The path on the host.</param>
/// <param name="Container">Where the engine sees it inside the container.</param>
public sealed record VolumeMount(string Host, string Container);
