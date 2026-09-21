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

//! Engine discovery: the order, and the message when it comes up empty.

mod common;

use std::collections::HashMap;
use std::path::PathBuf;

use cimvocabcheck::runtime::{ENV_BIN, ENV_IMAGE, ENV_JAR, ENV_NO_DOCKER};
use cimvocabcheck::{discover, Discovery, Engine, Error};
use common::{bare_env, TempDir};

fn env(pairs: &[(&str, &str)]) -> HashMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
        .collect()
}

#[test]
fn an_explicit_jar_path_beats_everything_else() {
    let dir = TempDir::new();
    let jar = dir.path().join("engine.jar");
    std::fs::write(&jar, b"").unwrap();
    let java = dir.script("java", "exit 0");

    let engine = discover(&Discovery {
        engine_path: Some(jar.clone()),
        env: Some(env(&[("PATH", dir.path().to_str().unwrap())])),
        ..Discovery::default()
    })
    .expect("the configured jar");

    assert_eq!(engine, Engine::Jar { jar, java });
}

#[test]
fn the_jar_environment_variable_comes_before_path() {
    let dir = TempDir::new();
    let jar = dir.path().join("engine.jar");
    std::fs::write(&jar, b"").unwrap();
    dir.script("java", "exit 0");
    dir.script("cimvocabcheck", "exit 0");

    let engine = discover(&Discovery {
        env: Some(env(&[
            ("PATH", dir.path().to_str().unwrap()),
            (ENV_JAR, jar.to_str().unwrap()),
        ])),
        ..Discovery::default()
    })
    .expect("the configured jar");

    assert!(matches!(engine, Engine::Jar { .. }), "{engine:?}");
}

#[test]
fn an_engine_on_path_is_used_when_the_environment_is_silent() {
    let dir = TempDir::new();
    let binary = dir.script("cimvocabcheck", "exit 0");

    let engine = discover(&Discovery {
        env: Some(env(&[("PATH", dir.path().to_str().unwrap())])),
        ..Discovery::default()
    })
    .expect("the engine on PATH");

    assert_eq!(engine, Engine::Binary { path: binary });
}

#[test]
fn docker_is_the_last_resort() {
    let dir = TempDir::new();
    let docker = dir.script("docker", "exit 0");

    let engine = discover(&Discovery {
        env: Some(env(&[("PATH", dir.path().to_str().unwrap())])),
        cwd: Some(dir.path().to_path_buf()),
        ..Discovery::default()
    })
    .expect("the docker fallback");

    match engine {
        Engine::Docker { docker: found, .. } => assert_eq!(found, docker),
        other => panic!("expected the docker fallback, got {other:?}"),
    }
}

#[test]
fn docker_can_be_switched_off() {
    let dir = TempDir::new();
    dir.script("docker", "exit 0");

    let error = discover(&Discovery {
        env: Some(env(&[
            ("PATH", dir.path().to_str().unwrap()),
            (ENV_NO_DOCKER, "1"),
        ])),
        ..Discovery::default()
    })
    .expect_err("docker was taken out of the chain");

    assert!(matches!(error, Error::EngineNotFound { .. }), "{error}");
}

#[test]
fn the_docker_image_is_overridable() {
    let dir = TempDir::new();
    dir.script("docker", "exit 0");

    let engine = discover(&Discovery {
        env: Some(env(&[
            ("PATH", dir.path().to_str().unwrap()),
            (ENV_IMAGE, "example.org/cvc:edge"),
        ])),
        cwd: Some(dir.path().to_path_buf()),
        ..Discovery::default()
    })
    .expect("the docker fallback");

    assert!(engine
        .command(&[], false, None)
        .contains(&"example.org/cvc:edge".to_owned()));
}

#[test]
fn failure_names_everything_that_was_tried() {
    let error = discover(&Discovery {
        env: Some(bare_env()),
        no_docker: true,
        ..Discovery::default()
    })
    .expect_err("nothing to find");

    let message = error.to_string();
    for expected in [ENV_JAR, ENV_BIN, "on PATH", "Docker"] {
        assert!(message.contains(expected), "{message}");
    }
}

#[test]
fn a_configured_engine_that_does_not_exist_says_so() {
    let error = discover(&Discovery {
        env: Some(env(&[("PATH", ""), (ENV_JAR, "/nowhere/engine.jar")])),
        ..Discovery::default()
    })
    .expect_err("the jar is not there");

    assert!(error.to_string().contains("/nowhere/engine.jar"), "{error}");
}

#[test]
fn a_jar_without_a_java_launcher_explains_the_requirement() {
    let dir = TempDir::new();
    let jar = dir.path().join("engine.jar");
    std::fs::write(&jar, b"").unwrap();

    let error = discover(&Discovery {
        engine_path: Some(jar),
        env: Some(bare_env()),
        ..Discovery::default()
    })
    .expect_err("there is no java");

    assert!(error.to_string().contains("JRE 21"), "{error}");
}

// ---- The Docker engine's path handling --------------------------------------------------------

#[test]
fn docker_mounts_the_working_directory_and_addresses_inputs_inside_it() {
    let dir = TempDir::new();
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: dir.path().to_path_buf(),
    };

    let command = engine.command(&["-f".into(), "json".into()], false, None);

    assert_eq!(&command[..3], ["docker", "run", "--rm"]);
    assert!(command.contains(&format!("{}:/work", dir.path().display())));
    assert_eq!(
        command[command.iter().position(|a| a == "-w").unwrap() + 1],
        "/work"
    );
    assert_eq!(
        engine.resolve_path("queries/q.rq", None).unwrap(),
        "queries/q.rq"
    );
    assert_eq!(
        engine
            .resolve_path(dir.path().join("queries/q.rq").to_str().unwrap(), None)
            .unwrap(),
        "queries/q.rq"
    );
}

#[test]
fn docker_keeps_stdin_open_only_when_it_is_used() {
    let dir = TempDir::new();
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: dir.path().to_path_buf(),
    };

    assert!(engine
        .command(&["-".into()], true, None)
        .contains(&"-i".to_owned()));
    assert!(!engine
        .command(&["q.rq".into()], false, None)
        .contains(&"-i".to_owned()));
}

#[test]
fn docker_uses_the_working_directory_of_the_call_not_of_its_discovery() {
    // An engine is discovered once and then used from several directories.
    let discovered_in = TempDir::new();
    let called_from = TempDir::new();
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: discovered_in.path().to_path_buf(),
    };

    let command = engine.command(&[], false, Some(called_from.path()));

    assert!(command.contains(&format!("{}:/work", called_from.path().display())));
}

#[test]
fn docker_refuses_a_path_it_could_not_mount() {
    let dir = TempDir::new();
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: dir.path().join("project"),
    };
    std::fs::create_dir_all(dir.path().join("project")).unwrap();

    let error = engine
        .resolve_path(dir.path().join("elsewhere/q.rq").to_str().unwrap(), None)
        .expect_err("outside the mount");

    assert!(
        error.to_string().contains("outside the working directory"),
        "{error}"
    );
}

#[test]
fn local_engines_pass_paths_through_untouched() {
    let engine = Engine::Binary {
        path: PathBuf::from("/opt/cvc"),
    };

    assert_eq!(
        engine.resolve_path("../shared/q.rq", None).unwrap(),
        "../shared/q.rq"
    );
}
