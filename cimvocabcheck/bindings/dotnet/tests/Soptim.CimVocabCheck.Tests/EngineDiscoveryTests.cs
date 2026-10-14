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

/// <summary>Engine discovery: the order, and the message when it comes up empty.</summary>
public sealed class EngineDiscoveryTests
{
    private static EngineDiscovery Discovery(TempDir directory, params (string Key, string Value)[] extra)
    {
        var environment = new Dictionary<string, string> { ["PATH"] = directory.Path };
        foreach (var (key, value) in extra)
        {
            environment[key] = value;
        }

        return new EngineDiscovery { Environment = environment, WorkingDirectory = directory.Path };
    }

    [Fact]
    public void AnExplicitJarPathBeatsEverythingElse()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var jar = Path.Combine(directory.Path, "engine.jar");
        File.WriteAllText(jar, string.Empty);
        var java = directory.Script("java", "exit 0");

        var engine = Engine.Discover(Discovery(directory) with { EnginePath = jar });

        var jarEngine = Assert.IsType<JarEngine>(engine);
        Assert.Equal(jar, jarEngine.Jar);
        Assert.Equal(java, jarEngine.Java);
    }

    [Fact]
    public void TheJarEnvironmentVariableComesBeforePath()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var jar = Path.Combine(directory.Path, "engine.jar");
        File.WriteAllText(jar, string.Empty);
        directory.Script("java", "exit 0");
        directory.Script("cimvocabcheck", "exit 0");

        var engine = Engine.Discover(Discovery(directory, (Engine.JarVariable, jar)));

        Assert.IsType<JarEngine>(engine);
    }

    [Fact]
    public void AnEngineOnPathIsUsedWhenTheEnvironmentIsSilent()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var binary = directory.Script("cimvocabcheck", "exit 0");

        var engine = Engine.Discover(Discovery(directory));

        Assert.Equal(binary, Assert.IsType<BinaryEngine>(engine).Path);
    }

    [Fact]
    public void DockerIsTheLastResort()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        var docker = directory.Script("docker", "exit 0");

        var engine = Engine.Discover(Discovery(directory));

        var dockerEngine = Assert.IsType<DockerEngine>(engine);
        Assert.Equal(docker, dockerEngine.Docker);
        Assert.Equal(Engine.DefaultImage, dockerEngine.Image);
    }

    [Fact]
    public void DockerCanBeSwitchedOff()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        directory.Script("docker", "exit 0");

        Assert.Throws<EngineNotFoundException>(
            () => Engine.Discover(Discovery(directory, (Engine.NoDockerVariable, "1"))));
    }

    [Fact]
    public void TheDockerImageIsOverridable()
    {
        Assert.SkipUnless(CanScript, "the fixtures are shell scripts");
        using var directory = new TempDir();
        directory.Script("docker", "exit 0");

        var engine = Engine.Discover(
            Discovery(directory, (Engine.ImageVariable, "example.org/cvc:edge")));

        Assert.Contains("example.org/cvc:edge", engine.BuildCommand([], false, null));
    }

    [Fact]
    public void FailureNamesEverythingThatWasTried()
    {
        using var directory = new TempDir();

        var error = Assert.Throws<EngineNotFoundException>(
            () => Engine.Discover(new EngineDiscovery
            {
                Environment = new Dictionary<string, string> { ["PATH"] = string.Empty },
                NoDocker = true,
            }));

        foreach (var expected in new[] { Engine.JarVariable, Engine.BinaryVariable, "on PATH", "Docker" })
        {
            Assert.Contains(expected, error.Message, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void AConfiguredEngineThatDoesNotExistSaysSo()
    {
        var error = Assert.Throws<EngineNotFoundException>(
            () => Engine.Discover(new EngineDiscovery
            {
                Environment = new Dictionary<string, string> { ["PATH"] = string.Empty },
                EnginePath = "/nowhere/engine.jar",
            }));

        Assert.Contains("/nowhere/engine.jar", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AJarWithoutAJavaLauncherExplainsTheRequirement()
    {
        using var directory = new TempDir();
        var jar = Path.Combine(directory.Path, "engine.jar");
        File.WriteAllText(jar, string.Empty);

        var error = Assert.Throws<EngineNotFoundException>(
            () => Engine.Discover(new EngineDiscovery
            {
                Environment = new Dictionary<string, string> { ["PATH"] = string.Empty },
                EnginePath = jar,
            }));

        Assert.Contains("JRE 21", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void DockerMountsTheWorkingDirectoryAndAddressesInputsInsideIt()
    {
        using var directory = new TempDir();
        var engine = new DockerEngine("cvc:test", "docker", directory.Path);

        var command = engine.BuildCommand(["-f", "json"], false, null);

        Assert.Equal(["docker", "run", "--rm"], command.Take(3));
        Assert.Contains($"{directory.Path}:/work", command);
        Assert.Equal("/work", command[command.ToList().IndexOf("-w") + 1]);
        Assert.Equal("queries/q.rq", engine.ResolvePath("queries/q.rq", null));
        Assert.Equal(
            "queries/q.rq",
            engine.ResolvePath(Path.Combine(directory.Path, "queries", "q.rq"), null));
    }

    [Fact]
    public void DockerMountsAConfigThatLiesAboveTheWorkingDirectory()
    {
        // Inside the container the engine's upward search stops at the mount, so it would miss the
        // config and silently check syntax only.
        using var directory = new TempDir();
        directory.Write("opencgmes.jsonc", "{}");
        directory.Write("queries/q.rq", "ASK {}");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "queries"));

        var command = engine.BuildCommand(["--format", "json", "--", "q.rq"], false, null);

        var mirror = DockerEngine.ContainerPath(directory.Path);
        Assert.Contains($"{directory.Path}:{mirror}:ro", command);
        Assert.Equal(
            ["--config", $"{mirror}/opencgmes.jsonc", "--format", "json", "--", "q.rq"],
            command.Skip(command.ToList().IndexOf("cvc:test") + 1));
    }

    [Fact]
    public void DockerPrefersTheJsoncConfigLikeTheEngineDoes()
    {
        using var directory = new TempDir();
        directory.Write("opencgmes.json", "{}");
        directory.Write("opencgmes.jsonc", "{}");
        directory.Write("sub/q.rq", "ASK {}");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "sub"));

        Assert.Contains(
            $"{DockerEngine.ContainerPath(directory.Path)}/opencgmes.jsonc",
            engine.BuildCommand(["q.rq"], false, null));
    }

    [Fact]
    public void DockerLeavesAConfigInsideTheMountToTheEngine()
    {
        using var directory = new TempDir();
        directory.Write("opencgmes.jsonc", "{}");
        directory.Write("sub/opencgmes.json", "{}");

        foreach (var cwd in new[] { directory.Path, Path.Combine(directory.Path, "sub") })
        {
            var command = new DockerEngine("cvc:test", "docker", cwd).BuildCommand(["q.rq"], false, null);
            Assert.DoesNotContain("--config", command);
            Assert.DoesNotContain(command, part => part.EndsWith(":ro", StringComparison.Ordinal));
        }
    }

    [Theory]
    [InlineData("--config", "x.jsonc")]
    [InlineData("--config=x.jsonc")]
    [InlineData("-cx.jsonc")]
    [InlineData("--schema", "profiles")]
    [InlineData("-s", "profiles")]
    [InlineData("--endpoint=http://h/q")]
    [InlineData("-e", "http://h/q")]
    public void DockerDoesNotAddAConfigWhenTheCallNamesASchemaSource(params string[] flags)
    {
        using var directory = new TempDir();
        directory.Write("opencgmes.jsonc", "{}");
        directory.Write("sub/q.rq", "ASK {}");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "sub"));

        var command = engine.BuildCommand([.. flags, "--", "q.rq"], false, null);

        Assert.DoesNotContain($"{DockerEngine.ContainerPath(directory.Path)}/opencgmes.jsonc", command);
    }

    [Fact]
    public void AnInputNamedLikeAFlagDoesNotSuppressTheConfig()
    {
        using var directory = new TempDir();
        directory.Write("opencgmes.jsonc", "{}");
        directory.Write("sub/q.rq", "ASK {}");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "sub"));

        Assert.Contains(
            $"{DockerEngine.ContainerPath(directory.Path)}/opencgmes.jsonc",
            engine.BuildCommand(["--", "-s.rq"], false, null));
    }

    [Fact]
    public void DockerKeepsStandardInputOpenOnlyWhenItIsUsed()
    {
        using var directory = new TempDir();
        var engine = new DockerEngine("cvc:test", "docker", directory.Path);

        Assert.Contains("-i", engine.BuildCommand(["-"], true, null));
        Assert.DoesNotContain("-i", engine.BuildCommand(["q.rq"], false, null));
    }

    [Fact]
    public void DockerUsesTheWorkingDirectoryOfTheCallNotOfItsDiscovery()
    {
        // An engine is discovered once and then used from several directories.
        using var discoveredIn = new TempDir();
        using var calledFrom = new TempDir();
        var engine = new DockerEngine("cvc:test", "docker", discoveredIn.Path);

        var command = engine.BuildCommand([], false, calledFrom.Path);

        Assert.Contains($"{calledFrom.Path}:/work", command);
    }

    [Fact]
    public void DockerRefusesAPathItCouldNotMount()
    {
        using var directory = new TempDir();
        var project = Path.Combine(directory.Path, "project");
        Directory.CreateDirectory(project);
        var engine = new DockerEngine("cvc:test", "docker", project);

        var error = Assert.Throws<CimVocabCheckException>(
            () => engine.ResolvePath(Path.Combine(directory.Path, "elsewhere", "q.rq"), null));

        Assert.Contains("outside the working directory", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void DockerAcceptsAnAbsolutePathSpelledThroughASymlink()
    {
        // macOS keeps /tmp and the temp directory under a symlinked /private, so the working
        // directory and an absolute input routinely disagree about which spelling they use.
        using var directory = new TempDir();
        var real = Path.Combine(directory.Path, "real");
        Directory.CreateDirectory(real);
        var link = Path.Combine(directory.Path, "link");
        Directory.CreateSymbolicLink(link, real);

        var throughLink = new DockerEngine("cvc:test", "docker", link);
        var throughReal = new DockerEngine("cvc:test", "docker", real);

        Assert.Equal("q.rq", throughLink.ResolvePath(Path.Combine(link, "q.rq"), null));
        Assert.Equal("q.rq", throughLink.ResolvePath(Path.Combine(real, "q.rq"), null));
        Assert.Equal("q.rq", throughReal.ResolvePath(Path.Combine(link, "q.rq"), null));
        Assert.Equal("sub/q.rq", throughReal.ResolvePath(Path.Combine(link, "sub", "q.rq"), null));
    }

    [Fact]
    public void DockerStillRefusesAPathASymlinkLeadsOutOf()
    {
        using var directory = new TempDir();
        var project = Path.Combine(directory.Path, "project");
        var elsewhere = Path.Combine(directory.Path, "elsewhere");
        Directory.CreateDirectory(project);
        Directory.CreateDirectory(elsewhere);
        var escape = Path.Combine(project, "escape");
        Directory.CreateSymbolicLink(escape, elsewhere);
        var engine = new DockerEngine("cvc:test", "docker", project);

        // Lexically inside, so it is passed through: Docker follows the link inside the container
        // and finds nothing, which the engine reports. Only a path no spelling puts inside is refused.
        Assert.Equal("escape/q.rq", engine.ResolvePath(Path.Combine(escape, "q.rq"), null));
        Assert.Throws<CimVocabCheckException>(
            () => engine.ResolvePath(Path.Combine(elsewhere, "q.rq"), null));
    }

    [Fact]
    public void DockerAddressesTheWorkingDirectoryItself()
    {
        using var directory = new TempDir();
        var engine = new DockerEngine("cvc:test", "docker", directory.Path);

        Assert.Equal(".", engine.ResolvePath(".", null));
        Assert.Equal(".", engine.ResolvePath(directory.Path, null));
        Assert.Equal(".", engine.ResolvePath(directory.Path + Path.DirectorySeparatorChar, null));
    }

    [Fact]
    public void DockerNamesEveryRunSoAKilledOneCanBeRemoved()
    {
        using var directory = new TempDir();
        var engine = new DockerEngine("cvc:test", "docker", directory.Path);

        static string NameOf(IReadOnlyList<string> command) =>
            command[command.ToList().IndexOf("--name") + 1];

        Assert.NotEqual(
            NameOf(engine.BuildCommand([], false, null)),
            NameOf(engine.BuildCommand([], false, null)));
    }

    [Fact]
    public void ARelativeEnginePathIsPinnedToWhereItWasFound()
    {
        // The engine may run in another directory, where the relative path means something else.
        using var directory = new TempDir();
        File.WriteAllText(Path.Combine(directory.Path, "cimvocabcheck"), string.Empty);
        var relative = Path.GetRelativePath(
            Directory.GetCurrentDirectory(), Path.Combine(directory.Path, "cimvocabcheck"));

        var engine = Engine.Discover(Discovery(directory) with { EnginePath = relative });

        Assert.Equal(
            Path.Combine(directory.Path, "cimvocabcheck"),
            Assert.IsType<BinaryEngine>(engine).Path);
    }

    [Fact]
    public void LocalEnginesPassPathsThroughUntouched()
    {
        Assert.Equal("../shared/q.rq", new BinaryEngine("/opt/cvc").ResolvePath("../shared/q.rq", null));
    }

    /// <summary>What <c>locale -a</c> lists on a machine with one locale beyond the built-in ones.</summary>
    private static readonly HashSet<string> Installed = ["C", "C.utf8", "POSIX", "en_US.utf8"];

    [Theory]
    [InlineData("", "", "", "", "LC_CTYPE")]
    [InlineData("", "", "C", "", "LC_CTYPE")]
    [InlineData("", "", "POSIX", "", "LC_CTYPE")]
    [InlineData("C", "", "en_US.UTF-8", "", "LC_ALL")]
    [InlineData("", "C", "C", "", "LC_CTYPE")]
    [InlineData("", "", "en_US.UTF-8", "", null)]
    [InlineData("", "en_US.UTF-8", "C", "", null)]
    [InlineData("", "", "en_US.utf8", "", null)]
    // Set but not installed: the C library then drops every category back to C, so only LC_ALL
    // gets the engine a UTF-8 charset again.
    [InlineData("", "", "xx_XX.UTF-8", "", "LC_ALL")]
    [InlineData("", "", "C.UTF-8", "xx_XX.UTF-8", "LC_ALL")]
    [InlineData("xx_XX.UTF-8", "", "", "", "LC_ALL")]
    public void ALocalEngineNeverRunsUnderAnAsciiOnlyLocale(
        string all, string ctype, string lang, string messages, string? expected)
    {
        Assert.SkipWhen(OperatingSystem.IsWindows(), "Windows takes no locale from the environment");
        var environment = new Dictionary<string, string?>
        {
            ["LC_ALL"] = all,
            ["LC_CTYPE"] = ctype,
            ["LANG"] = lang,
            ["LC_MESSAGES"] = messages,
        };

        foreach (Engine engine in new Engine[] { new JarEngine("cli.jar", "java"), new BinaryEngine("cimvocabcheck") })
        {
            Assert.Equal(
                expected is null ? null : (expected, "C.UTF-8"),
                engine.LocaleOverride(environment, Installed));
        }
    }

    [Fact]
    public void ALocaleIsTrustedWhenTheInstalledOnesCannotBeListed()
    {
        // Without a locale command (musl, say) nothing is known, and a named locale is kept.
        Assert.SkipWhen(OperatingSystem.IsWindows(), "Windows takes no locale from the environment");
        var environment = new Dictionary<string, string?> { ["LANG"] = "xx_XX.UTF-8" };

        Assert.Null(new JarEngine("cli.jar", "java").LocaleOverride(environment, null));
    }

    [Fact]
    public void TheDockerClientKeepsTheCallersLocale()
    {
        var engine = new DockerEngine("cvc:test", "docker", ".");

        Assert.Null(engine.LocaleOverride(new Dictionary<string, string?> { ["LANG"] = "C" }));
    }

    [Fact]
    public void DockerMountsAnExplicitConfigOutsideTheWorkingDirectory()
    {
        // The same config the engine would discover from there; naming it must not be refused.
        using var directory = new TempDir();
        directory.Write("opencgmes.jsonc", "{}");
        directory.Write("queries/q.rq", "ASK {}");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "queries"));
        var mounts = new List<VolumeMount>();

        var config = engine.ResolveConfig("../opencgmes.jsonc", null, mounts);
        var command = engine.BuildCommand(["--config", config, "--", "q.rq"], false, null, mounts);

        var mirror = DockerEngine.ContainerPath(RealDirectory(directory.Path));
        Assert.Equal($"{mirror}/opencgmes.jsonc", config);
        Assert.Contains($"{RealDirectory(directory.Path)}:{mirror}:ro", command);
    }

    private static readonly string[] SchemaPaths = ["../profiles", "../more/b.rdf", "profiles/inside.rdf"];

    [Fact]
    public void DockerMountsSchemasOutsideTheWorkingDirectoryWhereTheHostHasThem()
    {
        using var directory = new TempDir();
        directory.Write("profiles/a.rdf", "");
        directory.Write("more/b.rdf", "");
        directory.Write("project/profiles/inside.rdf", "");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "project"));
        var mounts = new List<VolumeMount>();

        var schemas = SchemaPaths
            .Select(path => engine.ResolveSchema(path, null, mounts))
            .ToList();
        var command = engine.BuildCommand([], false, null, mounts);

        var root = RealDirectory(directory.Path);
        string[] outside = [Path.Combine(root, "profiles"), Path.Combine(root, "more", "b.rdf")];
        Assert.Equal([.. outside.Select(DockerEngine.ContainerPath), "profiles/inside.rdf"], schemas);
        foreach (var host in outside)
        {
            Assert.Contains($"{host}:{DockerEngine.ContainerPath(host)}:ro", command);
        }
    }

    [Fact]
    public void DockerStillRefusesAMissingSchemaOutsideTheWorkingDirectory()
    {
        // Binding a path that does not exist makes Docker create an empty directory there.
        using var directory = new TempDir();
        Directory.CreateDirectory(Path.Combine(directory.Path, "project"));
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "project"));
        var mounts = new List<VolumeMount>();

        var error = Assert.Throws<CimVocabCheckException>(
            () => engine.ResolveSchema("../nowhere", null, mounts));

        Assert.Contains("outside the working directory", error.Message, StringComparison.Ordinal);
        Assert.Empty(mounts);
    }

    /// <summary>
    /// The host path the container sees at <paramref name="container"/>, going by the <c>-v</c>
    /// mounts in <paramref name="command"/>; <c>null</c> when nothing mounted covers it.
    /// </summary>
    private static string? SeenAs(IReadOnlyList<string> command, string container)
    {
        (string Host, string Target)? best = null;
        for (var at = 0; at + 1 < command.Count; at++)
        {
            if (command[at] != "-v")
            {
                continue;
            }

            var volume = command[at + 1].EndsWith(":ro", StringComparison.Ordinal)
                ? command[at + 1][..^3]
                : command[at + 1];
            var colon = volume.LastIndexOf(':');
            var (host, target) = (volume[..colon], volume[(colon + 1)..]);
            var covers = container == target || container.StartsWith(target + "/", StringComparison.Ordinal);
            if (covers && (best is null || target.Length > best.Value.Target.Length))
            {
                best = (host, target);
            }
        }

        if (best is not { } found)
        {
            return null;
        }

        var rest = container[found.Target.Length..].TrimStart('/');
        return rest.Length == 0 ? found.Host : Path.Combine(found.Host, rest.Replace('/', Path.DirectorySeparatorChar));
    }

    /// <summary>Where the engine resolves <paramref name="path"/> from the config it was handed.</summary>
    private static string WhereTheEngineLooks(string config, string path)
    {
        var segments = config.Split('/', StringSplitOptions.RemoveEmptyEntries).SkipLast(1).ToList();
        foreach (var segment in path.Split('/'))
        {
            if (segment == "..")
            {
                segments.RemoveAt(segments.Count - 1);
            }
            else if (segment is not ("" or "."))
            {
                segments.Add(segment);
            }
        }

        return "/" + string.Join('/', segments);
    }

    private static List<string> Volumes(IReadOnlyList<string> command) =>
        [.. command.Zip(command.Skip(1)).Where(pair => pair.First == "-v").Select(pair => pair.Second)];

    [Fact]
    public void DockerMountsTheSchemasAnExplicitConfigNamesOutsideItsDirectory()
    {
        // The config's relative paths are resolved against the config, which on the host lead out
        // of the directory mounted for it.
        using var directory = new TempDir();
        directory.Write("conf/opencgmes.jsonc", """
            {
              // shared between projects
              "cimvocabcheck": {
                "schemasDirectory": "../schemas/cgmes3",
                "schemas": ["../extra/x.rdf",],
              },
            }
            """);
        directory.Write("schemas/cgmes3/a.rdf", "");
        directory.Write("extra/x.rdf", "");
        directory.Write("proj/q.rq", "ASK {}");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "proj"));
        var mounts = new List<VolumeMount>();

        var config = engine.ResolveConfig("../conf/opencgmes.jsonc", null, mounts);
        var command = engine.BuildCommand(["--config", config, "--", "q.rq"], false, null, mounts);

        var root = RealDirectory(directory.Path);
        Assert.Equal(
            Path.Combine(root, "schemas", "cgmes3"),
            SeenAs(command, WhereTheEngineLooks(config, "../schemas/cgmes3")));
        Assert.Equal(
            Path.Combine(root, "extra", "x.rdf"),
            SeenAs(command, WhereTheEngineLooks(config, "../extra/x.rdf")));
    }

    [Fact]
    public void DockerMountsTheSchemasADiscoveredConfigNamesOutsideItsDirectory()
    {
        using var directory = new TempDir();
        directory.Write("repo/opencgmes.jsonc", """{"cimvocabcheck": {"schemasDirectory": "../profiles"}}""");
        directory.Write("profiles/a.rdf", "");
        directory.Write("repo/queries/q.rq", "ASK {}");

        var command = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "repo", "queries"))
            .BuildCommand(["--", "q.rq"], false, null);

        var config = command[command.ToList().IndexOf("--config") + 1];
        Assert.Equal(
            Path.Combine(directory.Path, "profiles"),
            SeenAs(command, WhereTheEngineLooks(config, "../profiles")));
    }

    [Fact]
    public void DockerMountsWhatAConfigInTheWorkingDirectoryNamesOutsideIt()
    {
        // The engine finds this config itself, at the mount, and resolves its paths from there.
        using var directory = new TempDir();
        directory.Write("proj/opencgmes.jsonc", """{"cimvocabcheck": {"schemasDirectory": "../shared"}}""");
        directory.Write("shared/a.rdf", "");

        var command = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "proj"))
            .BuildCommand(["--", "q.rq"], false, null);

        Assert.DoesNotContain("--config", command);
        Assert.Equal(
            Path.Combine(directory.Path, "shared"),
            SeenAs(command, WhereTheEngineLooks("/work/opencgmes.jsonc", "../shared")));
    }

    [Fact]
    public void DockerMountsAnAbsoluteSchemaPathAConfigNamesWhereItIs()
    {
        Assert.SkipWhen(OperatingSystem.IsWindows(), "a Windows path means nothing inside the container");
        using var directory = new TempDir();
        var absolute = Path.Combine(directory.Path, "abs");
        Directory.CreateDirectory(absolute);
        directory.Write(
            "proj/opencgmes.jsonc",
            $$$"""{"cimvocabcheck": {"schemasDirectory": "{{{absolute}}}"}}""");

        var command = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "proj"))
            .BuildCommand(["--", "q.rq"], false, null);

        Assert.Equal(absolute, SeenAs(command, absolute));
    }

    [Fact]
    public void DockerAddsNothingForConfigPathsInsideTheWorkingDirectory()
    {
        using var directory = new TempDir();
        directory.Write("opencgmes.jsonc", """{"cimvocabcheck": {"schemasDirectory": "profiles"}}""");
        directory.Write("profiles/a.rdf", "");

        var command = new DockerEngine("cvc:test", "docker", directory.Path).BuildCommand(["--", "q.rq"], false, null);

        Assert.Equal([$"{directory.Path}:/work"], Volumes(command));
    }

    [Theory]
    [InlineData("""{"cimvocabcheck": {"schemasDirectory": "../missing"}}""")]
    [InlineData("{ this is not json")]
    [InlineData("""{"cimvocabcheck": {"schemasDirectory": 3, "schemas": "x"}}""")]
    [InlineData("[]")]
    public void AConfigWhosePathsCannotBeUsedIsLeftToTheEngine(string text)
    {
        // The engine reports a missing schema or a broken config itself; binding a missing path
        // would make Docker create an empty directory in its place.
        using var directory = new TempDir();
        directory.Write("proj/opencgmes.jsonc", text);
        var project = Path.Combine(directory.Path, "proj");

        var command = new DockerEngine("cvc:test", "docker", project).BuildCommand(["--", "q.rq"], false, null);

        Assert.Equal([$"{project}:/work"], Volumes(command));
    }

    [Fact]
    public void DockerRefusesAConfigPathItWouldHaveToMountOverTheImage()
    {
        // ../usr from a config at /work is the container's own /usr.
        using var directory = new TempDir();
        directory.Write("proj/opencgmes.jsonc", """{"cimvocabcheck": {"schemasDirectory": "../usr"}}""");
        directory.Write("usr/a.rdf", "");
        var engine = new DockerEngine("cvc:test", "docker", Path.Combine(directory.Path, "proj"));

        var error = Assert.Throws<CimVocabCheckException>(() => engine.BuildCommand(["--", "q.rq"], false, null));

        Assert.Contains("/usr", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ContainerPathsMirrorTheHostPathUnderOneDirectory()
    {
        if (OperatingSystem.IsWindows())
        {
            Assert.Equal("/host/C/repo/profiles", DockerEngine.ContainerPath(@"C:\repo\profiles"));
        }
        else
        {
            Assert.Equal("/host/srv/repo/profiles", DockerEngine.ContainerPath("/srv/repo/profiles"));
        }
    }

    /// <summary>The temp root may sit behind a symlink (macOS /var → /private/var).</summary>
    private static string RealDirectory(string path) =>
        new DirectoryInfo(path).ResolveLinkTarget(returnFinalTarget: true)?.FullName ?? path;
}
