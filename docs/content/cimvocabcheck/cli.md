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

## Options

| Option | Argument | Description |
| --- | --- | --- |
| `-c`, `--config` | `<file>` | Config file. Default: auto-discovers `opencgmes.json` upward from the CWD |
| `-s`, `--schema` | `<file>` | Schema RDFS file(s). Repeatable. Alternative to `--config` |
| `-e`, `--endpoint` | `<url>` | SPARQL 1.1 endpoint hosting the CGMES schema; schema is loaded and graphs auto-mapped to profiles. See [Endpoints](/cimvocabcheck/endpoints) |
| `--strict-endpoint` | | Fail (exit 2) when an `--endpoint` exposes no CIM schema graphs, instead of falling back to syntax-only |
| `-p`, `--profile` | `<iri>` | Restrict to this profile IRI. Repeatable. Ignored when the config has `namedGraphs` |
| `-f`, `--format` | `text` \| `json` | Output format (default `text`). `json` matches the [API result shape](/cimvocabcheck/api#result-types) |
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
