---
title: Report Contract
sidebar_position: 11
---

# Report Contract

The machine-readable reports are CIMVocabCheck's integration surface for everything that is not
Java: CI systems, and tools or bindings written in .NET, Python, Rust, TypeScript, … They read a
report instead of calling the [API](/cimvocabcheck/api), so the report's shape is a published
contract rather than an implementation detail.

| `--format` | Document | Consumer |
| --- | --- | --- |
| `text` (default) | Compiler-style lines | Humans, terminal output |
| `json` | The report described on this page | Bindings, custom tooling, `jq` |
| `codequality` (alias `gitlab`) | [Code Quality](/cimvocabcheck/cli#code-quality-report) array | GitLab merge-request annotations |
| `sarif` | [SARIF 2.1.0](#sarif) log | GitHub code scanning, Azure DevOps, IDE viewers |

All of them are written to **stdout** — redirect them to a file. A one-line human summary goes to
stderr so a CI log still shows the outcome, and the [exit code](/cimvocabcheck/cli#exit-codes)
(`0` clean, `1` findings, `2` usage error) is unchanged by the format.

## The JSON report

```json
{
  "contractVersion": "1.0",
  "tool": { "name": "cimvocabcheck", "version": "1.4.2" },
  "summary": { "files": 1, "valid": 0, "invalid": 1, "errors": 1, "warnings": 0, "infos": 0 },
  "results": [
    {
      "file": "queries/line-segments.rq",
      "valid": false,
      "annotations": [
        {
          "severity": "ERROR",
          "code": "UNKNOWN_CLASS",
          "line": 3,
          "column": 12,
          "term": "http://iec.ch/TC57/CIM100#ACLineSegmentt",
          "message": "Class <...> does not exist in profile [Equipment].",
          "foundInOtherProfiles": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"]
        }
      ]
    }
  ]
}
```

| Field | Notes |
| --- | --- |
| `contractVersion` | Version of this contract, `"<major>.<minor>"` — **not** the tool version |
| `tool.name` / `tool.version` | Always `cimvocabcheck`; the version is `unknown` when the engine runs from a checkout rather than a released artifact |
| `summary.files` / `valid` / `invalid` | Input counts; a file is invalid when it has at least one `ERROR` after [strictness](/cimvocabcheck/configuration#strictness) |
| `summary.errors` / `warnings` / `infos` | Finding counts **for this document** — i.e. after the `--verbose` filter, so they always account for exactly what `results` contains |
| `results[].file` | The input as passed on the command line (`-` for stdin) — invoke with repository-relative paths |
| `results[].valid` | Decided before filtering, so it does not change with `--verbose` |
| `annotations[].severity` | `ERROR`, `WARN`, `INFO`, after strictness |
| `annotations[].code` | The [rule code](/cimvocabcheck/validation-checks) — key automation off this, never off `message` |
| `annotations[].line` / `column` | 1-based, when resolvable; columns count UTF-16 code units. Findings inside embedded SPARQL point at their line in the Turtle source |
| `annotations[].term` | The offending IRI, when the finding is about one term |
| `annotations[].graph` | Named graph the finding occurred in, under [per-graph scoping](/cimvocabcheck/configuration#namedgraphs) |
| `annotations[].message` | Human-readable; **not** stable across releases |
| `annotations[].foundInOtherProfiles` | For `UNKNOWN_CLASS`/`UNKNOWN_PROPERTY`: profiles where the term *does* exist — the "wrong profile in scope" hint |

Optional fields are omitted rather than emitted as `null`.

### JSON Schema

The report is published as a JSON Schema (draft-07) at
[`cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json`](https://github.com/SOPTIM/OpenCGMES/blob/main/cimvocabcheck/schemas/cimvocabcheck-report-1.schema.json).
Generate your binding's result types from it rather than hand-writing them; the file name carries
the contract **major**, so a future incompatible contract arrives as a new file next to this one.

## Compatibility

| Change | Contract bump | Consumer obligation |
| --- | --- | --- |
| New optional field | minor | Ignore unknown fields |
| New [rule code](/cimvocabcheck/validation-checks) | minor | Treat an unrecognised code as a generic finding — never as an error |
| New output format | minor | — |
| Rename/remove a field, rename a code, change what a code means | **major** | New schema file, announced in the release notes |

A consumer should accept any report whose `contractVersion` major matches the one it was written
against. Message texts and the *set* of findings can change in any release — that is the tool
improving, not a contract break.

## SARIF

`--format sarif` emits a SARIF 2.1.0 log: one run, whose driver declares a rule per code actually
reported, and one result per finding carrying its own `level` (`ERROR → error`, `WARN → warning`,
`INFO → note`). Because the level lives on the result, promoting severities with `--strictness` is
reflected in the log without redefining rules.

Artifact URIs are the file arguments verbatim, relative to `%SRCROOT%`, so invoke the CLI with
repository-relative paths. A clean run still emits a valid log with an empty `results` array —
uploading it is what clears previously reported alerts.

```yaml
- name: Validate SPARQL
  run: java -jar cimvocabcheck-cli.jar --schema schemas -f sarif queries/*.rq > cimvocabcheck.sarif
  continue-on-error: true   # findings must not skip the upload below
- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: cimvocabcheck.sarif
```

## Finding fingerprints

The `codequality` and `sarif` reports both carry a stable fingerprint per finding — `fingerprint`
in Code Quality, `partialFingerprints["cimvocabcheckFinding/v1"]` in SARIF — and **the same finding
has the same fingerprint in both**. It is derived from the file, code, position and offending term,
deliberately *not* from the rendered message, so a finding stays trackable across runs even when its
wording changes. Repeated identical findings in one run are disambiguated by their occurrence index.

## Writing a binding

- **Batch.** Loading a CGMES profile set costs about a second; validating a query after that costs
  almost nothing. Pass every input to one invocation instead of spawning per file.
- **Pass argv, not a shell string** — paths contain spaces.
- **Treat exit code `2` as a tool/config failure**, not as "invalid input": stdout holds no report
  in that case.
- **Pin the contract, not the wording.** Switch on `code`, tolerate codes you do not know, and
  ignore fields you do not know.
