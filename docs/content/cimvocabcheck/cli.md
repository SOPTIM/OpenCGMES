---
title: CLI
sidebar_position: 10
---

# Command-Line Interface

`cimvocabcheck-cli` validates SPARQL queries and SHACL shapes from the command line — built for CI
pipelines and pre-commit checks.

## Build

```bash
mvn -pl cimvocabcheck/cli package -DskipTests
# Output: cimvocabcheck/cli/target/cimvocabcheck-cli.jar
```

## Usage

```bash
java -jar cimvocabcheck-cli.jar [options] <file>...
```

`<file>` is one or more SPARQL/SHACL files; use `-` to read from stdin.

```bash
java -jar cimvocabcheck-cli.jar --help
java -jar cimvocabcheck-cli.jar --schema path/to/rdfs --strictness strict path/to/query.rq
```

## Docker

A schema-less image is published to the GitHub Container Registry. Bring your own schema and inputs
by mounting them — nothing is baked in:

```bash
docker pull ghcr.io/soptim/cimvocabcheck-cli:latest

# Validate shapes against a mounted schema directory, emitting a Code Quality report.
docker run --rm -v "$PWD:/work" ghcr.io/soptim/cimvocabcheck-cli:latest \
  --schema schemas -f codequality shapes/*.ttl > gl-code-quality-report.json
```

The working directory inside the image is `/work`, so mount your repo there and pass **repo-relative
paths** (that keeps a Code Quality report's `location.path` aligned with your source tree). `--schema`
accepts a directory, so a mounted schema folder works directly. The container's exit code is the
CLI's, so it drops straight into a CI gate. Tags: `:latest`, `:<version>` (per release), and `:edge`
(latest `main`).

Build the image yourself from a checkout (context is the repository root):

```bash
docker build -f cimvocabcheck/Dockerfile -t cimvocabcheck-cli .
```

## Options

| Option | Argument | Description |
| --- | --- | --- |
| `-c`, `--config` | `<file>` | Config file. Default: auto-discovers `opencgmes.json` upward from the CWD |
| `-s`, `--schema` | `<path>` | Schema RDFS file(s), or a directory of them (`.rdf`/`.ttl`/`.owl`). Repeatable. Alternative to `--config` |
| `-e`, `--endpoint` | `<url>` | SPARQL 1.1 endpoint hosting the CGMES schema; schema is loaded and graphs auto-mapped to profiles. See [Endpoints](/cimvocabcheck/endpoints) |
| `--strict-endpoint` | | Fail (exit 2) when an `--endpoint` exposes no CIM schema graphs, instead of falling back to syntax-only |
| `-p`, `--profile` | `<iri>` | Restrict to this profile IRI. Repeatable. Ignored when the config has `namedGraphs` |
| `-f`, `--format` | `text` \| `json` \| `codequality` | Output format (default `text`). `json` matches the [API result shape](/cimvocabcheck/api#result-types); `codequality` emits a [Code Quality report](#code-quality-report) (alias: `gitlab`) |
| `-v`, `--verbose` | | Also report `WARN` and `INFO` annotations (default: `ERROR` only) |
| `--strictness` | `<level>` | `permissive` \| `default` \| `strict` \| `pedantic`. Overrides `opencgmes.json` |
| `-h`, `--help` | | Show help |
| `-V`, `--version` | | Show version |

Schema resolution mirrors the editors: `--config`/`--schema`/`--endpoint`, else the nearest
`opencgmes.json`, else **syntax-only** (there is no bundled default schema). See
[Configuration](/cimvocabcheck/configuration).

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Valid — no errors |
| `1` | Validation found errors |
| `2` | Usage or configuration error (e.g. `--strict-endpoint` with no schema graphs) |

This makes it a drop-in CI gate:

```bash
# Fail the build on any query/shape error, promoting warnings to errors.
java -jar cimvocabcheck-cli.jar --strictness strict queries/*.rq
```

## Subcommands

### `init` — generate a config file

```bash
java -jar cimvocabcheck-cli.jar init
```

Writes a commented `opencgmes.json` starter (the same template the editors' **Create Config File**
command produces). See [Configuration](/cimvocabcheck/configuration).

### `explain` — print the static algebra plan

```bash
java -jar cimvocabcheck-cli.jar explain path/to/query.rq
```

A schema is optional; without one the static plan is shown. See
[Explain query](/cimvocabcheck/explain-query).

## JSON output

```bash
java -jar cimvocabcheck-cli.jar --format json --verbose query.rq
```

Emits the structured [`SparqlValidationResult`](/cimvocabcheck/api#result-types) as JSON — feed it
to `jq` or a CI annotation step.

## Code Quality report

```bash
java -jar cimvocabcheck-cli.jar --format codequality --schema path/to/rdfs \
  shapes/*.ttl queries/*.rq > gl-code-quality-report.json
```

`--format codequality` (alias `gitlab`) emits a
[Code Quality](https://docs.gitlab.com/ci/testing/code_quality/) report — a top-level JSON array
following the CodeClimate schema, one entry per finding:

```json
[
  {
    "description": "Class <...> does not exist in profile [Equipment].",
    "check_name": "UNKNOWN_CLASS",
    "severity": "major",
    "fingerprint": "a1b2c3…",
    "location": { "path": "shapes/equipment.ttl", "lines": { "begin": 3 } }
  }
]
```

Severities map `ERROR → major`, `WARN → minor`, `INFO → info`; pass `--verbose` to include `WARN`/
`INFO` findings. The `fingerprint` is a stable hash so a finding can be tracked across runs.

`location.path` is the file argument verbatim, so **invoke the CLI with paths relative to the
repository root** for the findings to line up with a merge-request diff. CI systems such as GitLab
consume the file as a `codequality` report artifact and render the findings inline; the non-zero exit
code on errors still fails the job.

