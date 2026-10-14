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

#[test]
fn docker_addresses_the_working_directory_itself() {
    let dir = TempDir::new();
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: dir.path().to_path_buf(),
    };

    assert_eq!(engine.resolve_path(".", None).unwrap(), ".");
    assert_eq!(
        engine
            .resolve_path(dir.path().to_str().unwrap(), None)
            .unwrap(),
        "."
    );
}

#[cfg(unix)]
#[test]
fn docker_accepts_an_absolute_path_spelled_through_a_symlink() {
    let dir = TempDir::new();
    let real = dir.path().join("real");
    std::fs::create_dir_all(&real).unwrap();
    let link = dir.path().join("link");
    std::os::unix::fs::symlink(&real, &link).unwrap();
    let docker = |cwd: &std::path::Path| Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: cwd.to_path_buf(),
    };

    for (cwd, input) in [(&link, &link), (&link, &real), (&real, &link)] {
        assert_eq!(
            docker(cwd)
                .resolve_path(input.join("q.rq").to_str().unwrap(), None)
                .unwrap(),
            "q.rq",
            "cwd {} input {}",
            cwd.display(),
            input.display()
        );
    }
    // Mounted as given: the daemon follows the link itself.
    assert!(docker(&link)
        .command(&[], false, None)
        .contains(&format!("{}:/work", link.display())));
}

#[test]
fn docker_names_every_run_so_a_killed_one_can_be_removed() {
    let dir = TempDir::new();
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: dir.path().to_path_buf(),
    };
    let name = |command: Vec<String>| {
        command[command.iter().position(|arg| arg == "--name").unwrap() + 1].clone()
    };

    assert_ne!(
        name(engine.command(&[], false, None)),
        name(engine.command(&[], false, None))
    );
}

#[test]
fn a_relative_engine_path_is_pinned_to_where_it_was_found() {
    // The engine may run in another directory, where the relative path means something else.
    // Tests run in the crate directory, so its manifest is a relative path that exists.
    let engine = discover(&Discovery {
        engine_path: Some(PathBuf::from("Cargo.toml")),
        env: Some(bare_env()),
        ..Discovery::default()
    })
    .unwrap();

    assert_eq!(
        engine,
        Engine::Binary {
            path: std::env::current_dir().unwrap().join("Cargo.toml")
        }
    );
}

fn docker_in(cwd: PathBuf) -> Engine {
    Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd,
    }
}

fn strings(args: &[&str]) -> Vec<String> {
    args.iter().map(|&arg| arg.to_owned()).collect()
}

#[test]
fn docker_mounts_a_config_that_lies_above_the_working_directory() {
    // Inside the container the engine's upward search stops at the mount, so it would miss the
    // config and silently check syntax only.
    let dir = TempDir::new();
    dir.write("opencgmes.jsonc", "{}");
    dir.write("queries/q.rq", "ASK {}");

    let command = docker_in(dir.path().join("queries")).command(
        &strings(&["--format", "json", "--", "q.rq"]),
        false,
        None,
    );

    assert!(command.contains(&format!("{}:/config:ro", dir.path().display())));
    let image = command.iter().position(|a| a == "cvc:test").unwrap();
    assert_eq!(
        command[image + 1..],
        strings(&[
            "--config",
            "/config/opencgmes.jsonc",
            "--format",
            "json",
            "--",
            "q.rq"
        ])
    );
}

#[test]
fn docker_prefers_the_jsonc_config_like_the_engine_does() {
    let dir = TempDir::new();
    dir.write("opencgmes.json", "{}");
    dir.write("opencgmes.jsonc", "{}");
    dir.write("sub/q.rq", "ASK {}");

    let command = docker_in(dir.path().join("sub")).command(&strings(&["q.rq"]), false, None);

    assert!(command.contains(&"/config/opencgmes.jsonc".to_owned()));
}

#[test]
fn docker_leaves_a_config_inside_the_mount_to_the_engine() {
    let dir = TempDir::new();
    dir.write("opencgmes.jsonc", "{}");
    dir.write("sub/opencgmes.json", "{}");

    for cwd in [dir.path().to_path_buf(), dir.path().join("sub")] {
        let command = docker_in(cwd).command(&strings(&["q.rq"]), false, None);
        assert!(!command.contains(&"--config".to_owned()), "{command:?}");
        assert!(!command.iter().any(|part| part.ends_with(":/config:ro")));
    }
}

#[test]
fn docker_does_not_add_a_config_when_the_call_names_a_schema_source() {
    let dir = TempDir::new();
    dir.write("opencgmes.jsonc", "{}");
    dir.write("sub/q.rq", "ASK {}");

    for flags in [
        &["--config", "x.jsonc"][..],
        &["--config=x.jsonc"],
        &["-cx.jsonc"],
        &["--schema", "profiles"],
        &["-s", "profiles"],
        &["--endpoint=http://h/q"],
        &["-e", "http://h/q"],
    ] {
        let mut args = strings(flags);
        args.extend(strings(&["--", "q.rq"]));
        let command = docker_in(dir.path().join("sub")).command(&args, false, None);
        assert!(
            !command.contains(&"/config/opencgmes.jsonc".to_owned()),
            "{flags:?}"
        );
    }
}

#[test]
fn an_input_named_like_a_flag_does_not_suppress_the_config() {
    let dir = TempDir::new();
    dir.write("opencgmes.jsonc", "{}");
    dir.write("sub/q.rq", "ASK {}");

    let command =
        docker_in(dir.path().join("sub")).command(&strings(&["--", "-s.rq"]), false, None);

    assert!(command.contains(&"/config/opencgmes.jsonc".to_owned()));
}

type LocaleCase<'a> = (&'a [(&'a str, &'a str)], Option<&'a str>);

#[cfg(unix)]
#[test]
fn a_local_engine_never_runs_under_an_ascii_only_locale() {
    let jar = Engine::Jar {
        jar: "cli.jar".into(),
        java: "java".into(),
    };
    let binary = Engine::Binary {
        path: "cimvocabcheck".into(),
    };
    // The environment, and the variable expected to be set to C.UTF-8.
    let cases: [LocaleCase; 7] = [
        (&[], Some("LC_CTYPE")),
        (&[("LANG", "C")], Some("LC_CTYPE")),
        (&[("LANG", "POSIX")], Some("LC_CTYPE")),
        (&[("LC_ALL", "C"), ("LANG", "de_DE.UTF-8")], Some("LC_ALL")),
        (&[("LC_CTYPE", "C"), ("LANG", "C")], Some("LC_CTYPE")),
        (&[("LANG", "de_DE.UTF-8")], None),
        (&[("LC_CTYPE", "en_US.UTF-8"), ("LANG", "C")], None),
    ];
    for (pairs, expected) in cases {
        let vars = env(pairs);
        let expected = expected.map(|variable| (variable, "C.UTF-8"));
        assert_eq!(jar.locale_override(Some(&vars)), expected, "{pairs:?}");
        assert_eq!(binary.locale_override(Some(&vars)), expected, "{pairs:?}");
    }
}

#[test]
fn the_docker_client_keeps_the_callers_locale() {
    let engine = Engine::Docker {
        image: "cvc:test".into(),
        docker: "docker".into(),
        cwd: ".".into(),
    };

    assert_eq!(engine.locale_override(Some(&env(&[("LANG", "C")]))), None);
}

#[test]
fn docker_mounts_an_explicit_config_outside_the_working_directory() {
    // The same config the engine would discover from there; naming it must not be refused.
    let dir = TempDir::new();
    dir.write("opencgmes.jsonc", "{}");
    dir.write("queries/q.rq", "ASK {}");
    let engine = docker_in(dir.path().join("queries"));
    let mut mounts = Vec::new();

    let config = engine
        .resolve_config("../opencgmes.jsonc", None, &mut mounts)
        .expect("mounted, not refused");
    let command = engine.command_with_mounts(
        &strings(&["--config", &config, "--", "q.rq"]),
        false,
        None,
        &mounts,
    );

    assert_eq!(config, "/config/opencgmes.jsonc");
    let directory = dir.path().canonicalize().unwrap();
    assert!(
        command.contains(&format!("{}:/config:ro", directory.display())),
        "{command:?}"
    );
}

#[test]
fn docker_mounts_schemas_outside_the_working_directory_one_each() {
    let dir = TempDir::new();
    dir.write("profiles/a.rdf", "");
    dir.write("more/b.rdf", "");
    dir.write("project/profiles/inside.rdf", "");
    let engine = docker_in(dir.path().join("project"));
    let mut mounts = Vec::new();

    let schemas: Vec<String> = ["../profiles", "../more/b.rdf", "profiles/inside.rdf"]
        .iter()
        .map(|path| engine.resolve_schema(path, None, &mut mounts).unwrap())
        .collect();
    let command = engine.command_with_mounts(&[], false, None, &mounts);

    assert_eq!(
        schemas,
        strings(&[
            "/schemas/0/profiles",
            "/schemas/1/b.rdf",
            "profiles/inside.rdf"
        ])
    );
    let root = dir.path().canonicalize().unwrap();
    for (host, container) in [
        ("profiles", "/schemas/0/profiles"),
        ("more/b.rdf", "/schemas/1/b.rdf"),
    ] {
        let volume = format!("{}:{container}:ro", root.join(host).display());
        assert!(command.contains(&volume), "{volume} in {command:?}");
    }
}

#[test]
fn docker_still_refuses_a_missing_schema_outside_the_working_directory() {
    // Binding a path that does not exist makes Docker create an empty directory there.
    let dir = TempDir::new();
    std::fs::create_dir_all(dir.path().join("project")).unwrap();
    let engine = docker_in(dir.path().join("project"));
    let mut mounts = Vec::new();

    let error = engine
        .resolve_schema("../nowhere", None, &mut mounts)
        .expect_err("refused");

    assert!(matches!(error, Error::UnreachablePath(_)), "{error}");
    assert_eq!(mounts, []);
}
