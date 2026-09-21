---
title: Python
sidebar_position: 12
---

# Python

`cimvocabcheck` on PyPI validates SPARQL queries and SHACL shapes from Python. It is a
**binding, not a second implementation**: it drives the [CLI](/cimvocabcheck/cli) and parses its
[report](/cimvocabcheck/report-contract), so a finding it returns is the same finding the editors,
the CI job and any other language binding produce.

```bash
pip install cimvocabcheck
```

Python 3.10 or newer, with no Python dependencies. It does need an engine to drive — see
[Finding an engine](#finding-an-engine).

## Validating

```python
from cimvocabcheck import validate

report = validate(["queries/line-segments.rq", "shapes/equipment.ttl"], schema="profiles/")

if not report.ok:
    for finding in report.errors:
        print(finding)   # queries/line-segments.rq:3:12: ERROR[UNKNOWN_CLASS] Class <…> …
```

Findings are **data, not exceptions**. A file with errors comes back as a report whose `ok` is
`False`; exceptions are reserved for "the engine could not run, or could not be trusted".

### One call, every input

Loading a CGMES profile set costs about a second, and validating one more query after that costs
almost nothing — so `validate()` takes a list and returns one report:

```python
from pathlib import Path

report = validate(sorted(Path("queries").glob("*.rq")), config="opencgmes.jsonc")
```

Spawning the engine per file turns a one-second job into a one-second-per-file job.
`validate_file()` exists for the genuinely single-file case and calls `validate()` underneath.
`validate_text()` validates a query held in memory by passing it on stdin; because the engine
tells SHACL from SPARQL by file suffix, shapes have to come from a file.

### Options

```python
validate(
    paths,
    schema="profiles/",                          # RDFS file(s) or directories; or …
    config="opencgmes.jsonc",                    # … a config file; or …
    endpoint="http://localhost:3030/ds/query",   # … a SPARQL endpoint
    profiles=["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
    strictness="strict",                         # permissive | default | strict | pedantic
    verbose=True,                                # include WARN/INFO — the default here
    cwd="…", timeout=120, extra_args=[],
)
```

With no `schema`, `config` or `endpoint` the engine discovers the nearest `opencgmes.jsonc` above
`cwd`; without one it checks [syntax only](/cimvocabcheck/configuration).

Pass inputs **relative to `cwd`**: they appear verbatim in the report, and CI annotations only
line up with a source tree when they are repository-relative.

`verbose` defaults to `True`, unlike the CLI. A library hands everything back and lets the caller
filter, rather than discarding findings the caller cannot then recover; pass `verbose=False` for
the CLI's errors-only behaviour.

## The result

| | |
| --- | --- |
| `report.ok` | no input has an `ERROR` |
| `report.summary` | counts, matching exactly what this report contains |
| `report.results` | one `FileResult` per input, in the order they were passed |
| `report.errors` / `warnings` / `infos` | flat findings, each carrying the file it came from |
| `report.findings(severity=…, code=…)` | the same, narrowed |
| `report.for_file(path)` | one input's result |
| `report.tool.version` | which engine produced this |

Each finding carries `severity`, `code`, `message` and — when the engine could resolve them —
`line`, `column`, `term`, `graph` and `found_in_other_profiles`.

**Switch on `code`, never on `message`**: messages change between releases, codes do not.

```python
from cimvocabcheck import Code

typos = report.findings(code=Code.UNKNOWN_CLASS) + report.findings(code=Code.UNKNOWN_PROPERTY)
```

## Finding an engine

The binding ships no engine. It discovers one in this order, and the error names every place it
looked:

| # | Source | |
| --- | --- | --- |
| 1 | the `engine=` argument | a path to a JAR or binary, or an `Engine` object |
| 2 | `$CIMVOCABCHECK_JAR`, then `$CIMVOCABCHECK_BIN` | a fat JAR (run with `java`), or an executable |
| 3 | `cimvocabcheck` on `PATH` | |
| 4 | a JAR bundled into the package | populated by a packaging step |
| 5 | `docker run ghcr.io/soptim/cimvocabcheck-cli:latest` | last resort |

A JAR engine needs a **JRE 21 or newer**, found via `JAVA_HOME` or `PATH`.

The Docker fallback mounts the working directory at the image's `/work`, so inputs must live
inside it — a path outside is refused rather than silently unreadable. `CIMVOCABCHECK_DOCKER_IMAGE`
pins a tag and `CIMVOCABCHECK_NO_DOCKER=1` takes it out of the chain. The first run pulls the
image, and an `endpoint` on `localhost` is then the *container's* localhost.

```bash
python -m cimvocabcheck --print-engine   # which engine would be used here
```

## Errors

| Exception | When |
| --- | --- |
| `EngineNotFoundError` | nothing to run; the message lists everywhere that was searched |
| `ToolError` | the engine reported a usage/config failure (exit `2`), or failed outright |
| `ReportParseError` | output was not a report of the contract major this binding targets |
| `EngineTimeoutError` | `timeout` elapsed |

`ToolError` carries `argv`, `returncode`, `stdout` and `stderr`.

## Command line and pre-commit

Installing the package puts a `cimvocabcheck` command on `PATH` that forwards to whichever engine
it discovers — same flags, same formats, same [exit codes](/cimvocabcheck/cli#exit-codes).
`python -m cimvocabcheck` does the same.

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

## Versions and compatibility

The package tracks the engine it was generated against (`0.7.x` binding ↔ `0.7.x` engine) and
accepts any engine declaring the same [report contract](/cimvocabcheck/report-contract) **major**.
Within a major the contract only ever adds, so the binding keeps fields it does not know in
`extra` and accepts codes it does not know — `annotation.is_known_code` tells you which — because
an unrecognised code is a generic finding, never an error.

The result model is **generated** from the published JSON Schema the package vendors; a contract
change is picked up by regenerating, and CI fails when the two have drifted apart.
