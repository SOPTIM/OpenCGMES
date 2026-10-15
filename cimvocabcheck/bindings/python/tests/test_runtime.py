#    Copyright (c) 2026 SOPTIM AG
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#
#    SPDX-License-Identifier: Apache-2.0

"""Engine discovery: the order, and the message when it comes up empty."""

from __future__ import annotations

import json
import os
import posixpath
import subprocess
import sys
import sysconfig
from itertools import pairwise
from pathlib import Path, PureWindowsPath

import pytest

from cimvocabcheck import (
    BinaryEngine,
    CimVocabCheckError,
    DockerEngine,
    EngineNotFoundError,
    JarEngine,
    UnreachablePathError,
    discover,
    runtime,
)

#: What ``locale -a`` lists on a machine with one locale beyond the built-in ones.
INSTALLED = frozenset({"C", "C.utf8", "POSIX", "en_US.utf8"})


@pytest.fixture
def bare_env(tmp_path):
    """An environment with no engine anywhere, so each test adds back only what it is about."""
    return {"PATH": str(tmp_path / "empty"), "JAVA_HOME": ""}


def executable(path, name):
    path.mkdir(parents=True, exist_ok=True)
    binary = path / name
    binary.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    binary.chmod(0o755)
    return binary


def test_an_explicit_engine_object_is_used_as_is(bare_env):
    engine = BinaryEngine("/opt/cimvocabcheck")

    assert discover(engine, env=bare_env) is engine


def test_an_explicit_jar_path_beats_everything_else(tmp_path, bare_env):
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"")
    java = executable(tmp_path / "jdk" / "bin", "java")

    engine = discover(jar, env=dict(bare_env, JAVA_HOME=str(tmp_path / "jdk")))

    assert isinstance(engine, JarEngine)
    assert engine.command(["--version"]) == [str(java), "-jar", str(jar), "--version"]


def test_the_jar_environment_variable_comes_before_path(tmp_path, bare_env):
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"")
    executable(tmp_path / "jdk" / "bin", "java")
    on_path = tmp_path / "bin"
    executable(on_path, "cimvocabcheck")

    engine = discover(
        env={
            "PATH": str(on_path),
            "JAVA_HOME": str(tmp_path / "jdk"),
            runtime.ENV_JAR: str(jar),
        }
    )

    assert isinstance(engine, JarEngine)


def test_an_engine_on_path_is_used_when_the_environment_is_silent(tmp_path, bare_env):
    binary = executable(tmp_path / "bin", "cimvocabcheck")

    engine = discover(env={"PATH": str(tmp_path / "bin")})

    assert isinstance(engine, BinaryEngine)
    assert engine.binary == binary


def shim(path, name="cimvocabcheck"):
    """A copy of this package's console script, as pip and uv generate it."""
    path.mkdir(parents=True, exist_ok=True)
    script = path / name
    script.write_text(
        f"#!{sys.executable}\n"
        "import sys\n"
        "from cimvocabcheck.__main__ import main\n"
        "sys.exit(main())\n",
        encoding="utf-8",
    )
    script.chmod(0o755)
    return script


def test_our_own_console_script_is_never_discovered_as_the_engine(tmp_path):
    """Otherwise the shim would exec itself, forever."""
    scripts = tmp_path / "venv" / "bin"
    shim(scripts)

    with pytest.raises(EngineNotFoundError):
        discover(env={"PATH": str(scripts)}, allow_docker=False)


def test_a_console_script_is_skipped_but_an_engine_after_it_is_found(tmp_path):
    shim(tmp_path / "venv" / "bin")
    binary = executable(tmp_path / "opt" / "bin", "cimvocabcheck")

    engine = discover(
        env={"PATH": os.pathsep.join([str(tmp_path / "venv" / "bin"), str(binary.parent)])},
        allow_docker=False,
    )

    assert isinstance(engine, BinaryEngine)
    assert engine.binary == binary


@pytest.mark.parametrize(
    "directory",
    [
        sysconfig.get_path("scripts"),
        sysconfig.get_path("scripts", scheme="nt_user" if os.name == "nt" else "posix_user"),
        os.path.dirname(sys.executable),
    ],
    ids=["scripts", "user-scripts", "interpreter"],
)
def test_an_engine_installed_where_python_keeps_its_scripts_is_found(directory, monkeypatch):
    """``/usr/local/bin``, ``~/.local/bin``, ``/usr/bin``, ``/opt/homebrew/bin``: common homes for
    an engine, and also where some interpreter keeps its console scripts. Only a copy of the shim
    may be skipped, never the directory."""
    candidate = os.path.join(directory, "cimvocabcheck")

    def which(name, path=None):
        return candidate if name == "cimvocabcheck" and path == directory else None

    monkeypatch.setattr(runtime.shutil, "which", which)
    monkeypatch.setattr(runtime, "_is_shim", lambda path: False)

    engine = discover(env={"PATH": directory}, allow_docker=False)

    assert isinstance(engine, BinaryEngine)
    assert str(engine.binary) == candidate


def test_directories_the_shim_chain_already_visited_are_skipped(tmp_path):
    """Another interpreter's shim is not in our script directory; the chain is what rules it out."""
    first, second = tmp_path / "py310" / "bin", tmp_path / "py313" / "bin"
    executable(first, "cimvocabcheck")
    binary = executable(second, "cimvocabcheck")

    engine = discover(
        env={
            "PATH": os.pathsep.join([str(first), str(second)]),
            runtime.ENV_SHIM_CHAIN: str(first),
        }
    )

    assert isinstance(engine, BinaryEngine)
    assert engine.binary == binary


def test_the_shim_records_where_it_forwards_to():
    engine = BinaryEngine("/opt/cimvocabcheck/bin/cimvocabcheck")

    env = runtime.shim_environment(engine, {runtime.ENV_SHIM_CHAIN: "/earlier"})

    chain = env[runtime.ENV_SHIM_CHAIN].split(os.pathsep)
    assert chain[0] == os.path.normcase(os.path.realpath("/earlier"))
    assert os.path.normcase(os.path.realpath("/opt/cimvocabcheck/bin")) in chain


def test_a_bin_variable_pointing_back_into_the_shim_chain_is_refused(tmp_path, bare_env):
    """Re-reading the same variable in the child would otherwise forward to the same shim again."""
    shim = executable(tmp_path / "bin", "cimvocabcheck")

    with pytest.raises(EngineNotFoundError) as raised:
        discover(
            env=dict(
                bare_env,
                CIMVOCABCHECK_BIN=str(shim),
                CIMVOCABCHECK_SHIM_CHAIN=str(tmp_path / "bin"),
            )
        )

    assert "shim" in str(raised.value)


@pytest.mark.skipif(os.name == "nt", reason="the fake installs are shebang scripts")
def test_two_installs_of_the_shim_on_path_do_not_forward_to_each_other(tmp_path):
    """What two interpreters on one ``PATH`` used to do: spawn each other until the host died."""
    installs = []
    for name in ("py310", "py313"):
        shim = tmp_path / name / "bin" / "cimvocabcheck"
        shim.parent.mkdir(parents=True)
        shim.write_text(
            f"#!{sys.executable}\n"
            "import sys\n"
            "from cimvocabcheck.__main__ import main\n"
            "sys.exit(main())\n",
            encoding="utf-8",
        )
        shim.chmod(0o755)
        installs.append(str(shim.parent))

    finished = subprocess.run(
        [os.path.join(installs[0], "cimvocabcheck"), "--version"],
        env={"PATH": os.pathsep.join(installs), runtime.ENV_NO_DOCKER: "1"},
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )

    assert finished.returncode == 2
    assert "no CIMVocabCheck engine found" in finished.stderr


def test_docker_is_the_last_resort(tmp_path):
    docker = executable(tmp_path / "bin", "docker")

    engine = discover(env={"PATH": str(tmp_path / "bin")}, cwd=tmp_path)

    assert isinstance(engine, DockerEngine)
    assert engine.docker == str(docker)
    assert engine.image == runtime.DEFAULT_IMAGE


def test_docker_can_be_switched_off(tmp_path):
    executable(tmp_path / "bin", "docker")

    with pytest.raises(EngineNotFoundError):
        discover(env={"PATH": str(tmp_path / "bin"), runtime.ENV_NO_DOCKER: "1"})


def test_the_docker_image_is_overridable(tmp_path):
    executable(tmp_path / "bin", "docker")

    engine = discover(
        env={"PATH": str(tmp_path / "bin"), runtime.ENV_IMAGE: "example.org/cvc:edge"},
        cwd=tmp_path,
    )

    assert "example.org/cvc:edge" in engine.command([])


def test_failure_names_everything_that_was_tried(bare_env):
    with pytest.raises(EngineNotFoundError) as raised:
        discover(env=bare_env, allow_docker=False)

    message = str(raised.value)
    for expected in (runtime.ENV_JAR, runtime.ENV_BIN, "on PATH", "bundled", "Docker"):
        assert expected in message


def test_a_configured_engine_that_does_not_exist_says_so(bare_env):
    with pytest.raises(EngineNotFoundError) as raised:
        discover(env=dict(bare_env, CIMVOCABCHECK_JAR="/nowhere/engine.jar"))

    assert "/nowhere/engine.jar" in str(raised.value)


def test_a_jar_without_a_java_launcher_explains_the_requirement(tmp_path, bare_env):
    jar = tmp_path / "engine.jar"
    jar.write_bytes(b"")

    with pytest.raises(EngineNotFoundError) as raised:
        discover(jar, env=bare_env)

    assert "JRE 21" in str(raised.value)


# ---- The Docker engine's path handling --------------------------------------------------------


def test_docker_mounts_the_working_directory_and_addresses_inputs_inside_it(tmp_path):
    engine = DockerEngine(image="cvc:test", docker="docker", cwd=tmp_path)

    command = engine.command(["-f", "json", "--", "queries/q.rq"], stdin=False)

    assert command[:3] == ["docker", "run", "--rm"]
    assert f"{tmp_path.resolve()}:/work" in command
    assert command[command.index("-w") + 1] == "/work"
    assert engine.resolve_path("queries/q.rq") == "queries/q.rq"
    assert engine.resolve_path(str(tmp_path / "queries" / "q.rq")) == "queries/q.rq"


def test_docker_keeps_stdin_open_only_when_it_is_used(tmp_path):
    engine = DockerEngine(cwd=tmp_path)

    assert "-i" in engine.command(["-"], stdin=True)
    assert "-i" not in engine.command(["q.rq"], stdin=False)


def test_docker_refuses_a_path_it_could_not_mount(tmp_path):
    engine = DockerEngine(cwd=tmp_path / "project")
    (tmp_path / "project").mkdir()

    with pytest.raises(CimVocabCheckError) as raised:
        engine.resolve_path(str(tmp_path / "elsewhere" / "q.rq"))

    assert isinstance(raised.value, UnreachablePathError)
    assert isinstance(raised.value, ValueError), "what earlier versions raised"
    assert "outside the working directory" in str(raised.value)


def test_local_engines_pass_paths_through_untouched(tmp_path):
    assert BinaryEngine("/opt/cvc").resolve_path("../shared/q.rq") == "../shared/q.rq"


def test_docker_accepts_an_absolute_path_spelled_through_a_symlink(tmp_path):
    real = tmp_path / "real"
    real.mkdir()
    link = tmp_path / "link"
    link.symlink_to(real, target_is_directory=True)

    through_link = DockerEngine(cwd=link)
    through_real = DockerEngine(cwd=real)

    assert through_link.resolve_path(str(link / "q.rq")) == "q.rq"
    assert through_link.resolve_path(str(real / "q.rq")) == "q.rq"
    assert through_real.resolve_path(str(link / "q.rq")) == "q.rq"


def test_docker_addresses_the_working_directory_itself(tmp_path):
    engine = DockerEngine(cwd=tmp_path)

    assert engine.resolve_path(".") == "."
    assert engine.resolve_path(str(tmp_path)) == "."


def test_a_relative_engine_path_is_pinned_to_where_it_was_found(tmp_path, bare_env, monkeypatch):
    """The engine may run in another directory, where the relative path means something else."""
    (tmp_path / "engine.jar").write_bytes(b"")
    executable(tmp_path / "jdk" / "bin", "java")
    monkeypatch.chdir(tmp_path)

    engine = discover(
        env=dict(bare_env, JAVA_HOME=str(tmp_path / "jdk"), CIMVOCABCHECK_JAR="engine.jar")
    )

    assert isinstance(engine, JarEngine)
    assert engine.jar == tmp_path / "engine.jar"


def test_a_killed_docker_run_removes_its_container(tmp_path):
    """Killing ``docker run`` stops the client only; the container would keep running."""
    log = tmp_path / "docker.log"
    docker = tmp_path / "bin" / "docker"
    docker.parent.mkdir()
    docker.write_text(f'#!/bin/sh\necho "$@" >> {log}\n', encoding="utf-8")
    docker.chmod(0o755)
    engine = DockerEngine(docker=str(docker), cwd=tmp_path)

    first = engine.command(["q.rq"])
    second = engine.command(["q.rq"])
    name = first[first.index("--name") + 1]
    assert name != second[second.index("--name") + 1], "every run needs a name of its own"

    engine.abandon(first)

    assert log.read_text(encoding="utf-8").split() == ["rm", "--force", name]


def test_docker_mounts_a_config_that_lies_above_the_working_directory(tmp_path):
    """Inside the container the engine's upward search stops at the mount, so it would miss the
    config and silently check syntax only."""
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    queries = tmp_path / "queries"
    queries.mkdir()
    engine = DockerEngine(docker="docker", cwd=queries)

    command = engine.command(["--format", "json", "--", "q.rq"])

    mirror = runtime.container_path(tmp_path.resolve())
    assert f"{tmp_path.resolve()}:{mirror}:ro" in command
    image = command.index(engine.image)
    assert command[image + 1 :] == [
        "--config",
        f"{mirror}/opencgmes.jsonc",
        "--format",
        "json",
        "--",
        "q.rq",
    ]


@pytest.mark.parametrize(
    ("args", "expected"),
    [
        (["explain", "q.rq"], ["explain", "--config", "{config}", "q.rq"]),
        (
            ["--format", "json", "explain", "q.rq"],
            ["--format", "json", "explain", "--config", "{config}", "q.rq"],
        ),
        (["--format", "explain", "q.rq"], ["--config", "{config}", "--format", "explain", "q.rq"]),
        (["--", "explain"], ["--config", "{config}", "--", "explain"]),
    ],
)
def test_docker_hands_a_config_above_the_mount_to_the_command_that_reads_it(
    tmp_path, args, expected
):
    """A flag before a subcommand belongs to the top level: ``explain`` would discover nothing and
    explain without the schema."""
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()
    engine = DockerEngine(cwd=tmp_path / "sub")
    config = f"{runtime.container_path(tmp_path.resolve())}/opencgmes.jsonc"

    command = engine.command(args)

    assert command[command.index(engine.image) + 1 :] == [
        config if part == "{config}" else part for part in expected
    ]


@pytest.mark.parametrize("args", [["init", "--dir", "."], ["help", "explain"]])
def test_docker_hands_no_config_to_a_subcommand_that_reads_no_schema(tmp_path, args):
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()
    engine = DockerEngine(cwd=tmp_path / "sub")

    command = engine.command(args)

    assert command[command.index(engine.image) + 1 :] == args
    assert not any(part.endswith(":ro") for part in command)


def test_docker_prefers_the_jsonc_config_like_the_engine_does(tmp_path):
    for name in ("opencgmes.json", "opencgmes.jsonc"):
        (tmp_path / name).write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()

    command = DockerEngine(cwd=tmp_path / "sub").command(["q.rq"])

    assert f"{runtime.container_path(tmp_path.resolve())}/opencgmes.jsonc" in command


def test_docker_leaves_a_config_inside_the_mount_to_the_engine(tmp_path):
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "opencgmes.json").write_text("{}", encoding="utf-8")

    for cwd in (tmp_path, tmp_path / "sub"):
        command = DockerEngine(cwd=cwd).command(["q.rq"])
        assert "--config" not in command
        assert not any(part.endswith(":ro") for part in command)


@pytest.mark.parametrize(
    "flags",
    [
        ["--config", "x.jsonc"],
        ["--config=x.jsonc"],
        ["-cx.jsonc"],
        ["--schema", "profiles"],
        ["-s", "profiles"],
        ["--endpoint=http://h/q"],
        ["-e", "http://h/q"],
    ],
)
def test_docker_does_not_add_a_config_when_the_call_names_a_schema_source(tmp_path, flags):
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()

    command = DockerEngine(cwd=tmp_path / "sub").command([*flags, "--", "q.rq"])

    assert f"{runtime.container_path(tmp_path.resolve())}/opencgmes.jsonc" not in command


def test_an_input_named_like_a_flag_does_not_suppress_the_config(tmp_path):
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()

    command = DockerEngine(cwd=tmp_path / "sub").command(["--", "-s.rq"])

    assert f"{runtime.container_path(tmp_path.resolve())}/opencgmes.jsonc" in command


@pytest.mark.skipif(os.name == "nt", reason="Windows takes no locale from the environment")
@pytest.mark.parametrize(
    ("env", "expected"),
    [
        ({}, {"LC_CTYPE": "C.UTF-8"}),
        ({"LANG": "C"}, {"LANG": "C", "LC_CTYPE": "C.UTF-8"}),
        ({"LANG": "POSIX"}, {"LANG": "POSIX", "LC_CTYPE": "C.UTF-8"}),
        ({"LC_ALL": "C", "LANG": "de_DE.UTF-8"}, {"LC_ALL": "C.UTF-8", "LANG": "de_DE.UTF-8"}),
        ({"LC_CTYPE": "C", "LANG": "C"}, {"LC_CTYPE": "C.UTF-8", "LANG": "C"}),
        ({"LANG": "en_US.UTF-8"}, {"LANG": "en_US.UTF-8"}),
        ({"LC_CTYPE": "en_US.UTF-8", "LANG": "C"}, {"LC_CTYPE": "en_US.UTF-8", "LANG": "C"}),
        # Set but not installed: the C library then drops every category back to C, so only
        # LC_ALL gets the engine a UTF-8 charset again.
        ({"LANG": "xx_XX.UTF-8"}, {"LANG": "xx_XX.UTF-8", "LC_ALL": "C.UTF-8"}),
        (
            {"LANG": "C.UTF-8", "LC_MESSAGES": "xx_XX.UTF-8"},
            {"LANG": "C.UTF-8", "LC_MESSAGES": "xx_XX.UTF-8", "LC_ALL": "C.UTF-8"},
        ),
        ({"LC_ALL": "xx_XX.UTF-8"}, {"LC_ALL": "C.UTF-8"}),
        # An unset LC_ALL is skipped, and the spellings of one codeset are one locale.
        ({"LC_ALL": "", "LANG": "en_US.utf8"}, {"LC_ALL": "", "LANG": "en_US.utf8"}),
        # Not every LC_ variable names a locale: terminals set these, and ssh forwards them.
        (
            {"LANG": "en_US.UTF-8", "LC_TERMINAL": "iTerm2", "LC_TERMINAL_VERSION": "3.5.0"},
            {"LANG": "en_US.UTF-8", "LC_TERMINAL": "iTerm2", "LC_TERMINAL_VERSION": "3.5.0"},
        ),
    ],
)
def test_a_local_engine_never_runs_under_an_ascii_only_locale(env, expected, monkeypatch):
    monkeypatch.setattr(runtime, "installed_locales", lambda: INSTALLED)
    assert JarEngine("cli.jar", java="java").environment(env) == expected
    assert BinaryEngine("cimvocabcheck").environment(env) == expected


@pytest.mark.skipif(os.name == "nt", reason="Windows takes no locale from the environment")
def test_a_locale_is_trusted_when_the_installed_ones_cannot_be_listed(monkeypatch):
    """Without a ``locale`` command (musl, say) nothing is known, and a named locale is kept."""
    monkeypatch.setattr(runtime, "installed_locales", lambda: None)

    assert JarEngine("cli.jar", java="java").environment({"LANG": "xx_XX.UTF-8"}) == {
        "LANG": "xx_XX.UTF-8"
    }


def test_installed_locales_are_read_from_the_locale_command(tmp_path, monkeypatch):
    executable(tmp_path, "locale").write_text(
        "#!/bin/sh\nprintf 'C\\nC.utf8\\nPOSIX\\nen_US.utf8\\n'\n", encoding="utf-8"
    )
    monkeypatch.setenv("PATH", str(tmp_path))
    runtime.installed_locales.cache_clear()
    try:
        assert runtime.installed_locales() == frozenset({"C", "C.utf8", "POSIX", "en_US.utf8"})
        monkeypatch.setenv("PATH", str(tmp_path / "nowhere"))
        runtime.installed_locales.cache_clear()
        assert runtime.installed_locales() is None
    finally:
        runtime.installed_locales.cache_clear()


@pytest.mark.skipif(os.name == "nt", reason="Windows takes no locale from the environment")
def test_an_inherited_locale_is_left_to_inheritance_when_it_is_already_usable(monkeypatch):
    monkeypatch.setenv("LC_ALL", "C.UTF-8")

    assert JarEngine("cli.jar", java="java").environment() is None


def test_the_docker_client_keeps_the_callers_environment(tmp_path):
    engine = DockerEngine(cwd=tmp_path)

    assert engine.environment({"LANG": "C"}) == {"LANG": "C"}
    assert engine.environment() is None


def test_docker_mounts_an_explicit_config_outside_the_working_directory(tmp_path):
    """The same config the engine would discover from there; naming it must not be refused."""
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    queries = tmp_path / "queries"
    queries.mkdir()
    engine = DockerEngine(docker="docker", cwd=queries)
    mounts: list[runtime.Mount] = []

    config = engine.resolve_config("../opencgmes.jsonc", None, mounts)
    command = engine.command(["--config", config, "--", "q.rq"], mounts=mounts)

    mirror = runtime.container_path(tmp_path.resolve())
    assert config == f"{mirror}/opencgmes.jsonc"
    assert f"{tmp_path.resolve()}:{mirror}:ro" in command


def test_docker_mounts_schemas_outside_the_working_directory_where_the_host_has_them(tmp_path):
    for name in ("profiles/a.rdf", "more/b.rdf", "project/profiles/inside.rdf"):
        (tmp_path / name).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / name).write_text("", encoding="utf-8")
    engine = DockerEngine(docker="docker", cwd=tmp_path / "project")
    mounts: list[runtime.Mount] = []

    schemas = [
        engine.resolve_schema(path, None, mounts)
        for path in ("../profiles", "../more/b.rdf", "profiles/inside.rdf")
    ]
    command = engine.command([], mounts=mounts)

    root = tmp_path.resolve()
    outside = [root / "profiles", root / "more" / "b.rdf"]
    assert schemas == [*map(runtime.container_path, outside), "profiles/inside.rdf"]
    for host in outside:
        assert f"{host}:{runtime.container_path(host)}:ro" in command


def test_docker_still_refuses_a_missing_schema_outside_the_working_directory(tmp_path):
    """Binding a path that does not exist makes Docker create an empty directory there."""
    (tmp_path / "project").mkdir()
    engine = DockerEngine(docker="docker", cwd=tmp_path / "project")
    mounts: list[runtime.Mount] = []

    with pytest.raises(UnreachablePathError):
        engine.resolve_schema("../nowhere", None, mounts)
    assert mounts == []


def seen_as(command, container):
    """The host path the container sees at ``container``, going by the ``-v`` mounts in
    ``command``; ``None`` when nothing mounted covers it."""
    best = None
    for flag, value in pairwise(command):
        if flag != "-v":
            continue
        host, target = value.removesuffix(":ro").rsplit(":", 1)
        covers = container == target or container.startswith(target.rstrip("/") + "/")
        if covers and (best is None or len(target) > len(best[1])):
            best = (host, target)
    if best is None:
        return None
    host, target = best
    rest = container[len(target) :].lstrip("/")
    return Path(host) / rest if rest else Path(host)


def config_path(directory, text):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "opencgmes.jsonc").write_text(text, encoding="utf-8")
    return directory / "opencgmes.jsonc"


def config_argument(command):
    return command[command.index("--config") + 1]


def where_the_engine_looks(config, path):
    """Where the engine resolves ``path`` from the config it was handed, inside the container."""
    return posixpath.normpath(posixpath.join(posixpath.dirname(config), path))


def test_docker_mounts_the_schemas_an_explicit_config_names_outside_its_directory(tmp_path):
    """The config's relative paths are resolved against the config, which on the host lead out of
    the directory mounted for it."""
    config_path(
        tmp_path / "conf",
        """{
          // shared between projects
          "cimvocabcheck": {
            "schemasDirectory": "../schemas/cgmes3",
            "schemas": ["../extra/x.rdf",],
          },
        }""",
    )
    (tmp_path / "schemas" / "cgmes3").mkdir(parents=True)
    (tmp_path / "extra").mkdir()
    (tmp_path / "extra" / "x.rdf").write_text("", encoding="utf-8")
    (tmp_path / "proj").mkdir()
    engine = DockerEngine(docker="docker", cwd=tmp_path / "proj")
    mounts: list[runtime.Mount] = []

    config = engine.resolve_config("../conf/opencgmes.jsonc", None, mounts)
    command = engine.command(["--config", config, "--", "q.rq"], mounts=mounts)

    root = tmp_path.resolve()
    for path, host in (("../schemas/cgmes3", "schemas/cgmes3"), ("../extra/x.rdf", "extra/x.rdf")):
        assert seen_as(command, where_the_engine_looks(config, path)) == root / host


def test_docker_mounts_the_schemas_a_discovered_config_names_outside_its_directory(tmp_path):
    config_path(tmp_path / "repo", '{"cimvocabcheck": {"schemasDirectory": "../profiles"}}')
    (tmp_path / "profiles").mkdir()
    (tmp_path / "repo" / "queries").mkdir()

    command = DockerEngine(cwd=tmp_path / "repo" / "queries").command(["--", "q.rq"])

    config = config_argument(command)
    assert seen_as(command, config) == (tmp_path / "repo" / "opencgmes.jsonc").resolve()
    looked_at = where_the_engine_looks(config, "../profiles")
    assert seen_as(command, looked_at) == (tmp_path / "profiles").resolve()


def test_docker_mounts_what_a_config_in_the_working_directory_names_outside_it(tmp_path):
    """The engine finds this config itself, at the mount, and resolves its paths from there."""
    config_path(tmp_path / "proj", '{"cimvocabcheck": {"schemasDirectory": "../shared"}}')
    (tmp_path / "shared").mkdir()

    command = DockerEngine(cwd=tmp_path / "proj").command(["--", "q.rq"])

    assert "--config" not in command
    looked_at = where_the_engine_looks("/work/opencgmes.jsonc", "../shared")
    assert seen_as(command, looked_at) == (tmp_path / "shared").resolve()


@pytest.mark.skipif(os.name == "nt", reason="a Windows path means nothing inside the container")
def test_docker_mounts_an_absolute_schema_path_a_config_names_where_it_is(tmp_path):
    absolute = (tmp_path / "abs").resolve()
    absolute.mkdir()
    config_path(
        tmp_path / "proj", json.dumps({"cimvocabcheck": {"schemasDirectory": str(absolute)}})
    )

    command = DockerEngine(cwd=tmp_path / "proj").command(["--", "q.rq"])

    assert seen_as(command, absolute.as_posix()) == absolute


def test_docker_adds_nothing_for_config_paths_inside_the_working_directory(tmp_path):
    config_path(tmp_path, '{"cimvocabcheck": {"schemasDirectory": "profiles"}}')
    (tmp_path / "profiles").mkdir()

    command = DockerEngine(cwd=tmp_path).command(["--", "q.rq"])

    assert [value for flag, value in pairwise(command) if flag == "-v"] == [
        f"{tmp_path.resolve()}:/work"
    ]


@pytest.mark.parametrize(
    "text",
    [
        '{"cimvocabcheck": {"schemasDirectory": "../missing"}}',
        "{ this is not json",
        '{"cimvocabcheck": {"schemasDirectory": 3, "schemas": "x"}}',
        "[]",
    ],
)
def test_a_config_whose_paths_cannot_be_used_is_left_to_the_engine(tmp_path, text):
    """The engine reports a missing schema or a broken config itself; binding a missing path would
    make Docker create an empty directory in its place."""
    config_path(tmp_path / "proj", text)

    command = DockerEngine(cwd=tmp_path / "proj").command(["--", "q.rq"])

    assert [value for flag, value in pairwise(command) if flag == "-v"] == [
        f"{(tmp_path / 'proj').resolve()}:/work"
    ]


def test_docker_refuses_a_config_path_it_would_have_to_mount_over_the_image(tmp_path):
    """``../usr`` from a config at ``/work`` is the container's own ``/usr``."""
    config_path(tmp_path / "proj", '{"cimvocabcheck": {"schemasDirectory": "../usr"}}')
    (tmp_path / "usr").mkdir()

    with pytest.raises(UnreachablePathError, match="/usr"):
        DockerEngine(cwd=tmp_path / "proj").command(["--", "q.rq"])


def test_container_paths_mirror_the_host_path_under_one_directory():
    assert runtime.container_path(Path("/srv/repo/profiles")) == "/host/srv/repo/profiles"
    assert runtime.container_path(PureWindowsPath(r"C:\repo\profiles")) == "/host/C/repo/profiles"
