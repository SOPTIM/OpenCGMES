# cimvocabcheck (Rust)

Validate SPARQL queries and SHACL shapes against CIM/CGMES schema profiles from Rust.

This crate is a **binding, not an engine**. The analysis — SPARQL parsing, the algebra walk, the
CGMES profile model, SHACL — is one Apache Jena implementation shared by every consumer, so a
finding you get here is the same finding your editor, your CI job and a colleague's Python script
get. What this crate adds is finding that engine, building its command line, and giving you its
published report as typed Rust.

Full documentation: <https://opencgmes.soptim.de/cimvocabcheck/rust>

## Install

```toml
[dependencies]
cimvocabcheck = "0.1"
```

Rust 1.74+. `serde` is the whole dependency list — the subprocess timeout included, everything
else is `std`. You do need an engine to drive; see [Finding an engine](#finding-an-engine).

## Use it

```rust,no_run
use cimvocabcheck::{validate, Options};

let report = validate(
    &["queries/line-segments.rq", "shapes/equipment.ttl"],
    &Options::new().schema("profiles"),
)?;

for finding in report.errors() {
    println!("{finding}");   // queries/line-segments.rq:3:12: ERROR[UNKNOWN_CLASS] Class <…> …
}
# Ok::<(), cimvocabcheck::Error>(())
```

Findings are **data, not errors**: a file with errors comes back as a report whose `ok()` is
`false`. `Error` is reserved for "the engine could not run, or could not be trusted".

### Validate everything in one call

Loading a CGMES profile set costs about a second; validating one more query after that costs
almost nothing. `validate` therefore takes a slice — spawning the engine per file turns a
one-second job into a one-second-per-file job.

### Reading the report

```rust,ignore
report.ok()                     // no input has an ERROR
report.summary.errors           // counts, matching exactly what this report contains
report.results                  // one FileResult per input, in the order they were passed
report.errors()                 // flat findings, each carrying the file it came from
report.of_code(Code::UNKNOWN_CLASS)
report.at_least(Severity::WARN)
report.for_file("queries/line-segments.rq")
report.tool.version             // which engine produced this
```

Each finding carries `severity`, `code`, `message`, and — when the engine could resolve them —
`line`, `column`, `term`, `graph` and `found_in_other_profiles`.

**Match on `code`, never on `message`.** Messages change between releases; codes do not.

### Options

```rust,ignore
Options::new()
    .schema("profiles")                            // RDFS file(s) or directories; or …
    .config("opencgmes.jsonc")                     // … a config file; or …
    .endpoint("http://localhost:3030/ds/query")    // … a SPARQL endpoint
    .profile("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")
    .strictness(Strictness::STRICT)
    .verbose(false)                                // WARN/INFO are included by default
    .cwd("…")
    .timeout(Duration::from_secs(120))
```

With no schema, config or endpoint the engine discovers the nearest `opencgmes.jsonc` above the
working directory; without one it checks syntax only.

Pass inputs **relative to `cwd`**: they appear verbatim in the report, and CI annotations only
line up with a source tree when they are repository-relative.

`verbose` defaults to `true`, unlike the CLI. A library hands back everything and lets you
filter, rather than discarding findings you cannot then recover.

## Finding an engine

`discover` looks in this order, and the error names every place it looked:

| # | Source | |
| --- | --- | --- |
| 1 | `Options::engine` / `Options::engine_path` | an `Engine`, or a path to a JAR or binary |
| 2 | `$CIMVOCABCHECK_JAR`, then `$CIMVOCABCHECK_BIN` | a fat JAR (run with `java`), or an executable |
| 3 | `cimvocabcheck` on `PATH` | |
| 4 | `docker run ghcr.io/soptim/cimvocabcheck-cli:latest` | last resort |

A JAR engine needs a **JRE 21 or newer**, found via `JAVA_HOME` or `PATH`.

The Docker fallback mounts the working directory at the image's `/work`, so inputs must live
inside it — a path outside is refused rather than silently unreadable. `CIMVOCABCHECK_DOCKER_IMAGE`
pins a tag and `CIMVOCABCHECK_NO_DOCKER=1` takes it out of the chain. The first run pulls the
image, and an `endpoint` on `localhost` is then the *container's* localhost.

## Errors

| Variant | When |
| --- | --- |
| `EngineNotFound` | nothing to run; carries every location that was searched |
| `EngineMissing` | a configured engine path does not exist |
| `Tool` | the engine reported a usage/config failure (exit `2`), or failed outright |
| `ReportParse` | output was not a report of the contract major this crate targets |
| `Timeout` | the engine did not finish in time |

`Error::failure()` returns the command, exit code, stdout and stderr, so a CI log can show what
actually happened.

## Versioning and compatibility

This crate tracks the engine it was generated against (`0.7.x` crate ↔ `0.7.x` engine) and
accepts any engine declaring the same **report contract major**. Within a major the contract only
ever adds — new optional fields, new rule codes, new output formats — so the crate:

- keeps fields it does not know in each type's `extra` map rather than rejecting them, and
- accepts codes it does not know (`Annotation::is_known_code` tells you), because the contract
  requires an unrecognised code to be treated as a generic finding, never as an error.

The contract itself is documented at <https://opencgmes.soptim.de/cimvocabcheck/report-contract>.

## Developing

`src/model.rs` and `src/codes.rs` are **generated** from the published JSON Schema — edit the
generator, not the model. Conveniences live in `src/ergonomics.rs` and survive regeneration.

```bash
python3 ../codegen/generate_models.py --target rust            # regenerate after a contract change
python3 ../codegen/generate_models.py --target rust --check    # what CI runs
cargo clippy --all-targets && cargo fmt -- --check
cargo test                                                     # engine tests skip without an engine
CIMVOCABCHECK_JAR=../../cli/target/cimvocabcheck-cli-*.jar cargo test
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
