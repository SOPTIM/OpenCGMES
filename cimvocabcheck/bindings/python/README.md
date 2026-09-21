# cimvocabcheck (Python)

Validate SPARQL queries and SHACL shapes against CIM/CGMES schema profiles from Python.

This package is a **binding, not an engine**. The analysis — SPARQL parsing, the algebra walk,
the CGMES profile model, SHACL — is one Apache Jena implementation shared by every consumer, so a
finding you get here is the same finding your editor, your CI job and your colleague's .NET
service get. What this package adds is finding that engine, building its command line, and giving
you its published report as typed Python.

Full documentation: <https://opencgmes.soptim.de/cimvocabcheck/python>

## Install

```bash
pip install cimvocabcheck
```

Python 3.10+. The package has **no Python dependencies**. It does need an engine to drive — see
[Finding an engine](#finding-an-engine); the simplest is a JRE 21+ and the CLI fat JAR.

## Use it

```python
from cimvocabcheck import validate

report = validate(["queries/line-segments.rq", "shapes/equipment.ttl"], schema="profiles/")

if not report.ok:
    for finding in report.errors:
        print(finding)            # queries/line-segments.rq:3:12: ERROR[UNKNOWN_CLASS] Class …
```

Findings are **data, not exceptions**: a file with errors comes back as a report whose `ok` is
`False`. Exceptions are reserved for "the engine could not run or could not be trusted".

### Validate everything in one call

Loading a CGMES profile set costs about a second; validating one more query after that costs
almost nothing. `validate()` therefore takes a **list**:

```python
report = validate(sorted(Path("queries").glob("*.rq")), config="opencgmes.jsonc")
```

Spawning the engine once per file turns a one-second job into a one-second-per-file job.
`validate_file()` exists for the genuinely single-file case and simply calls `validate()`.

### Reading the report

```python
report.ok                      # no input has an ERROR
report.summary.errors          # counts, matching exactly what this report contains
report.results                 # one FileResult per input, in the order they were passed
report.errors                  # flat findings, each carrying the file it came from
report.findings(code=Code.UNKNOWN_CLASS)
report.for_file("queries/line-segments.rq")
report.tool.version            # which engine produced this
```

Each finding carries `severity`, `code`, `message`, and — when the engine could resolve them —
`line`, `column`, `term`, `graph` and `found_in_other_profiles` (the "you have the wrong profile
in scope" hint).

**Switch on `code`, never on `message`.** Messages change between releases; codes do not.

```python
from cimvocabcheck import Code

typos = report.findings(code=Code.UNKNOWN_CLASS) + report.findings(code=Code.UNKNOWN_PROPERTY)
```

### Options

```python
validate(
    paths,
    schema="profiles/",        # RDFS file(s) or directories; or …
    config="opencgmes.jsonc",  # … a config file; or …
    endpoint="http://localhost:3030/ds/query",   # … a SPARQL endpoint
    profiles=["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
    strictness="strict",       # permissive | default | strict | pedantic
    verbose=True,              # include WARN/INFO — the default here, unlike the CLI
    cwd="…", timeout=120,
)
```

With no `schema`, `config` or `endpoint`, the engine discovers the nearest `opencgmes.jsonc` above
`cwd`; without one, it checks syntax only.

Pass inputs as paths **relative to `cwd`**. They appear verbatim in the report, and CI annotations
only line up with your source tree when they are repository-relative.

`verbose` defaults to `True` here on purpose: a library hands back everything and lets you filter,
rather than discarding findings you cannot then recover. Pass `verbose=False` for the CLI's
errors-only behaviour.

## Finding an engine

`validate()` discovers one in this order, and the error names every place it looked:

| # | Source | |
| --- | --- | --- |
| 1 | the `engine=` argument | a path to a JAR or binary, or an `Engine` |
| 2 | `$CIMVOCABCHECK_JAR`, then `$CIMVOCABCHECK_BIN` | a fat JAR (run with `java`), or an executable |
| 3 | `cimvocabcheck` on `PATH` | |
| 4 | a JAR bundled into this package | populated by a packaging step, not by the source tree |
| 5 | `docker run ghcr.io/soptim/cimvocabcheck-cli:latest` | last resort |

A JAR engine needs a **JRE 21 or newer**, found via `JAVA_HOME` or `PATH`.

The Docker fallback mounts the working directory at the image's `/work`, so inputs must live
inside it — an outside path is refused rather than silently unreadable. Set
`CIMVOCABCHECK_DOCKER_IMAGE` to pin a tag, or `CIMVOCABCHECK_NO_DOCKER=1` to take it out of the
chain. Note that the first run pulls the image, and that an `endpoint` on `localhost` is the
container's localhost, not yours.

```bash
python -m cimvocabcheck --print-engine     # which engine would be used here
```

## Errors

| Exception | When |
| --- | --- |
| `EngineNotFoundError` | nothing to run; the message lists everywhere that was searched |
| `ToolError` | the engine reported a usage/config failure (exit 2), or failed outright |
| `ReportParseError` | output was not a report of the contract major this binding targets |
| `EngineTimeoutError` | `timeout` elapsed |

`ToolError` carries `argv`, `returncode`, `stdout` and `stderr`, so a CI log can show what
actually happened.

## Command line

Installing the package puts a `cimvocabcheck` command on your `PATH` that forwards to whichever
engine it discovers — same flags, same output formats, same exit codes (`0` clean, `1` findings,
`2` usage). `python -m cimvocabcheck` does the same.

```bash
cimvocabcheck --schema profiles/ -f sarif queries/*.rq > cimvocabcheck.sarif
```

## pre-commit

```yaml
repos:
  - repo: local
    hooks:
      - id: cimvocabcheck
        name: CIMVocabCheck
        entry: cimvocabcheck
        language: python
        additional_dependencies: ["cimvocabcheck"]
        files: \.(rq|sparql|ttl|shacl)$
        require_serial: true
```

`require_serial` matters: without it pre-commit splits the files across processes and each one
reloads the profile set.

## Versioning and compatibility

This package tracks the engine it was generated against (`0.7.x` binding ↔ `0.7.x` engine) and
accepts any engine declaring the same **report contract major**. Within a major the contract only
ever adds — new optional fields, new rule codes, new output formats — so the binding:

- keeps fields it does not know in `extra` rather than dropping them, and
- accepts codes it does not know (`annotation.is_known_code` tells you), because the contract
  requires an unrecognised code to be treated as a generic finding, never as an error.

The contract itself is documented at
<https://opencgmes.soptim.de/cimvocabcheck/report-contract>.

## Developing

The result model is **generated** from the JSON Schema this package vendors — edit the generator,
not the model. Convenience methods live in `_ergonomics.py` and survive regeneration.

```bash
python scripts/generate_model.py            # regenerate after a contract change
python scripts/generate_model.py --check    # what CI runs; fails with a diff when stale
python -m pytest                            # engine-backed tests skip when no engine is found
CIMVOCABCHECK_JAR=../../cli/target/cimvocabcheck-cli-*.jar python -m pytest
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
