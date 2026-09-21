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
    public void LocalEnginesPassPathsThroughUntouched()
    {
        Assert.Equal("../shared/q.rq", new BinaryEngine("/opt/cvc").ResolvePath("../shared/q.rq", null));
    }
}
