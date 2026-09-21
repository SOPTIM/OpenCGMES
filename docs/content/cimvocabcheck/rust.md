---
title: Rust
sidebar_position: 14
---

# Rust

The `cimvocabcheck` crate validates SPARQL queries and SHACL shapes from Rust. It is a
**binding, not a second implementation**: it drives the [CLI](/cimvocabcheck/cli) and parses its
[report](/cimvocabcheck/report-contract), so a finding it returns is the same finding the editors,
the CI job and any other language binding produce.

```toml
[dependencies]
cimvocabcheck = "0.1"
```

Rust 1.74+. `serde` is the whole dependency list — the subprocess timeout included, everything
else is `std`. It does need an engine to drive — see [Finding an engine](#finding-an-engine).

## Validating

```rust
use cimvocabcheck::{validate, Options};

let report = validate(
    &["queries/line-segments.rq", "shapes/equipment.ttl"],
    &Options::new().schema("profiles"),
)?;

for finding in report.errors() {
    println!("{finding}");   // queries/line-segments.rq:3:12: ERROR[UNKNOWN_CLASS] …
}
```

Findings are **data, not errors**. A file with errors comes back as a report whose `ok()` is
`false`; `Error` is reserved for "the engine could not run, or could not be trusted".

### One call, every input

Loading a CGMES profile set costs about a second, and validating one more query after that costs
almost nothing — so `validate` takes a slice and returns one report. Spawning the engine per file
turns a one-second job into a one-second-per-file job. `validate_text` validates a query held in
memory by passing it on stdin; because the engine tells SHACL from SPARQL by file suffix, shapes
have to come from a file.

### Options

```rust
Options::new()
    .schema("profiles")                            // RDFS file(s) or directories; or …
    .config("opencgmes.jsonc")                     // … a config file; or …
    .endpoint("http://localhost:3030/ds/query")    // … a SPARQL endpoint
    .profile("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")
    .strictness(Strictness::STRICT)                // permissive | default | strict | pedantic
    .verbose(false)                                // WARN/INFO are included by default
    .cwd("…")
    .timeout(Duration::from_secs(120))
```

With no schema, config or endpoint the engine discovers the nearest `opencgmes.jsonc` above the
working directory; without one it checks [syntax only](/cimvocabcheck/configuration).

Pass inputs **relative to `cwd`**: they appear verbatim in the report, and CI annotations only
line up with a source tree when they are repository-relative.

`verbose` defaults to `true`, unlike the CLI. A library hands everything back and lets the caller
filter, rather than discarding findings the caller cannot then recover.

## The result

| | |
| --- | --- |
| `report.ok()` | no input has an `ERROR` |
| `report.summary` | counts, matching exactly what this report contains |
| `report.results` | one `FileResult` per input, in the order they were passed |
| `report.errors()` / `warnings()` / `infos()` | flat findings, each carrying the file it came from |
| `report.of_code(…)` / `at_least(…)` | the same, narrowed |
| `report.for_file(path)` | one input's result |
| `report.tool.version` | which engine produced this |

**Match on `code`, never on `message`**: messages change between releases, codes do not.

```rust
let typos = [Code::UNKNOWN_CLASS, Code::UNKNOWN_PROPERTY]
    .iter()
    .flat_map(|code| report.of_code(code))
    .collect::<Vec<_>>();
```

## Finding an engine

`discover` searches in this order, and the error names every place it looked:

| # | Source | |
| --- | --- | --- |
| 1 | `Options::engine` / `engine_path` | an `Engine`, or a path to a JAR or binary |
| 2 | `$CIMVOCABCHECK_JAR`, then `$CIMVOCABCHECK_BIN` | a fat JAR (run with `java`), or an executable |
| 3 | `cimvocabcheck` on `PATH` | |
| 4 | `docker run ghcr.io/soptim/cimvocabcheck-cli:latest` | last resort |

A JAR engine needs a **JRE 21 or newer**, found via `JAVA_HOME` or `PATH`. The Docker fallback
mounts the working directory at the image's `/work`, so inputs must live inside it — see the
[Python page](/cimvocabcheck/python#finding-an-engine) for the shared details, which are the same
for every binding.

## Errors

| Variant | When |
| --- | --- |
| `EngineNotFound` | nothing to run; carries every location that was searched |
| `EngineMissing` | a configured engine path does not exist |
| `Tool` | the engine reported a usage/config failure (exit `2`), or failed outright |
| `ReportParse` | output was not a report of the contract major this crate targets |
| `Timeout` | the engine did not finish in time |

`Error::failure()` returns the command, exit code, stdout and stderr.

## Versions and compatibility

The crate tracks the engine it was generated against (`0.7.x` crate ↔ `0.7.x` engine) and accepts
any engine declaring the same [report contract](/cimvocabcheck/report-contract) **major**. Within
a major the contract only ever adds, so the crate keeps fields it does not know in each type's
`extra` map and accepts codes it does not know — `Annotation::is_known_code` tells you which.

The model is **generated** from the published JSON Schema by the generator shared with the Python
and .NET bindings; CI fails when the two have drifted apart.
