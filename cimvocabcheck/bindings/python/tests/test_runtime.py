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

import os
import subprocess
import sys
import sysconfig

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

    assert f"{tmp_path.resolve()}:/config:ro" in command
    image = command.index(engine.image)
    assert command[image + 1 :] == [
        "--config",
        "/config/opencgmes.jsonc",
        "--format",
        "json",
        "--",
        "q.rq",
    ]


def test_docker_prefers_the_jsonc_config_like_the_engine_does(tmp_path):
    for name in ("opencgmes.json", "opencgmes.jsonc"):
        (tmp_path / name).write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()

    command = DockerEngine(cwd=tmp_path / "sub").command(["q.rq"])

    assert "/config/opencgmes.jsonc" in command


def test_docker_leaves_a_config_inside_the_mount_to_the_engine(tmp_path):
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "opencgmes.json").write_text("{}", encoding="utf-8")

    for cwd in (tmp_path, tmp_path / "sub"):
        command = DockerEngine(cwd=cwd).command(["q.rq"])
        assert "--config" not in command
        assert not any(part.endswith(":/config:ro") for part in command)


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

    assert "/config/opencgmes.jsonc" not in command


def test_an_input_named_like_a_flag_does_not_suppress_the_config(tmp_path):
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "sub").mkdir()

    command = DockerEngine(cwd=tmp_path / "sub").command(["--", "-s.rq"])

    assert "/config/opencgmes.jsonc" in command


@pytest.mark.skipif(os.name == "nt", reason="Windows takes no locale from the environment")
@pytest.mark.parametrize(
    ("env", "expected"),
    [
        ({}, {"LC_CTYPE": "C.UTF-8"}),
        ({"LANG": "C"}, {"LANG": "C", "LC_CTYPE": "C.UTF-8"}),
        ({"LANG": "POSIX"}, {"LANG": "POSIX", "LC_CTYPE": "C.UTF-8"}),
        ({"LC_ALL": "C", "LANG": "de_DE.UTF-8"}, {"LC_ALL": "C.UTF-8", "LANG": "de_DE.UTF-8"}),
        ({"LC_CTYPE": "C", "LANG": "C"}, {"LC_CTYPE": "C.UTF-8", "LANG": "C"}),
        ({"LANG": "de_DE.UTF-8"}, {"LANG": "de_DE.UTF-8"}),
        ({"LC_CTYPE": "en_US.UTF-8", "LANG": "C"}, {"LC_CTYPE": "en_US.UTF-8", "LANG": "C"}),
    ],
)
def test_a_local_engine_never_runs_under_an_ascii_only_locale(env, expected):
    assert JarEngine("cli.jar", java="java").environment(env) == expected
    assert BinaryEngine("cimvocabcheck").environment(env) == expected


@pytest.mark.skipif(os.name == "nt", reason="Windows takes no locale from the environment")
def test_an_inherited_locale_is_left_to_inheritance_when_it_is_already_usable(monkeypatch):
    monkeypatch.setenv("LC_ALL", "C.UTF-8")

    assert JarEngine("cli.jar", java="java").environment() is None


def test_the_docker_client_keeps_the_callers_environment(tmp_path):
    engine = DockerEngine(cwd=tmp_path)

    assert engine.environment({"LANG": "C"}) == {"LANG": "C"}
    assert engine.environment() is None
