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

"""The ``cimvocabcheck`` command over the Docker engine: what it hands ``docker run``."""

from __future__ import annotations

import os

import pytest

from cimvocabcheck import DockerEngine, runtime
from cimvocabcheck import __main__ as shim

pytestmark = pytest.mark.skipif(os.name == "nt", reason="the stand-in docker is a shell script")


@pytest.fixture
def docker(tmp_path, monkeypatch):
    """Runs the shim from ``tmp/proj`` with a stand-in ``docker`` that records its arguments.

    Returns a function that runs the shim and gives back its exit code and the recorded argv.
    """
    log = tmp_path / "docker.log"
    fake = tmp_path / "bin" / "docker"
    fake.parent.mkdir()
    fake.write_text(f"#!/bin/sh\nprintf '%s\\n' \"$@\" > '{log}'\n", encoding="utf-8")
    fake.chmod(0o755)
    project = tmp_path / "proj"
    project.mkdir()
    monkeypatch.chdir(project)
    engine = DockerEngine(docker=str(fake), cwd=project)
    monkeypatch.setattr(shim, "discover", lambda: engine)

    def run(*args):
        code = shim.main(list(args))
        argv = log.read_text(encoding="utf-8").splitlines() if log.exists() else None
        return code, argv

    return run


def engine_arguments(argv):
    return argv[argv.index(DockerEngine().image) + 1 :]


def test_the_shim_mounts_a_config_and_schema_outside_the_working_directory(tmp_path, docker):
    (tmp_path / "conf").mkdir()
    (tmp_path / "conf" / "opencgmes.jsonc").write_text("{}", encoding="utf-8")
    (tmp_path / "profiles").mkdir()

    code, argv = docker("--config", "../conf/opencgmes.jsonc", "-s", "../profiles", "q.rq")

    assert code == 0
    conf, profiles = (tmp_path / "conf").resolve(), (tmp_path / "profiles").resolve()
    assert engine_arguments(argv) == [
        "--config",
        f"{runtime.container_path(conf)}/opencgmes.jsonc",
        "-s",
        runtime.container_path(profiles),
        "q.rq",
    ]
    assert f"{conf}:{runtime.container_path(conf)}:ro" in argv
    assert f"{profiles}:{runtime.container_path(profiles)}:ro" in argv


@pytest.mark.parametrize(
    ("given", "flag"),
    [
        (["--config=../conf/opencgmes.jsonc"], "--config="),
        (["-c=../conf/opencgmes.jsonc"], "-c="),
        (["-c../conf/opencgmes.jsonc"], "-c"),
    ],
)
def test_the_shim_rewrites_every_spelling_of_an_option_value(tmp_path, docker, given, flag):
    (tmp_path / "conf").mkdir()
    (tmp_path / "conf" / "opencgmes.jsonc").write_text("{}", encoding="utf-8")

    _, argv = docker(*given, "q.rq")

    mirror = runtime.container_path((tmp_path / "conf").resolve())
    assert engine_arguments(argv) == [f"{flag}{mirror}/opencgmes.jsonc", "q.rq"]


def test_the_shim_refuses_an_input_outside_the_working_directory(tmp_path, docker, capsys):
    (tmp_path / "elsewhere.rq").write_text("", encoding="utf-8")

    code, argv = docker("../elsewhere.rq")

    assert code == 2
    assert argv is None
    assert "outside the working directory" in capsys.readouterr().err


def test_the_shim_addresses_inputs_relative_to_the_mount(tmp_path, docker):
    (tmp_path / "proj" / "q.rq").write_text("", encoding="utf-8")

    _, argv = docker("-f", "json", str((tmp_path / "proj" / "q.rq").resolve()), "--", "-", "x.rq")

    assert engine_arguments(argv) == ["-f", "json", "q.rq", "--", "-", "x.rq"]


def test_the_shim_knows_which_options_of_a_subcommand_take_a_value(tmp_path, docker):
    """``-f`` is ``--format`` for validation but ``--force`` for ``init``."""
    _, argv = docker("init", "-f", "-d", ".")
    assert engine_arguments(argv) == ["init", "-f", "-d", "."]

    (tmp_path / "profiles").mkdir()
    _, argv = docker("explain", "-s", "../profiles", "q.rq")
    mirror = runtime.container_path((tmp_path / "profiles").resolve())
    assert engine_arguments(argv) == ["explain", "-s", mirror, "q.rq"]


def test_the_shim_passes_values_that_are_not_paths_through(docker):
    args = ["-e", "http://h/q", "--profile", "http://p", "--strictness=strict", "-v", "q.rq"]

    _, argv = docker(*args)

    assert engine_arguments(argv) == args


def test_the_shim_hands_a_config_above_the_working_directory_to_explain(tmp_path, docker):
    """``explain`` reads the schema too; a ``--config`` in front of it would go to the top level."""
    (tmp_path / "opencgmes.jsonc").write_text("{}", encoding="utf-8")

    _, argv = docker("explain", "q.rq")

    mirror = runtime.container_path(tmp_path.resolve())
    assert engine_arguments(argv) == ["explain", "--config", f"{mirror}/opencgmes.jsonc", "q.rq"]
