---
title: .NET
sidebar_position: 13
---

# .NET

`Soptim.CimVocabCheck` on NuGet validates SPARQL queries and SHACL shapes from .NET. It is a
**binding, not a second implementation**: it drives the [CLI](/cimvocabcheck/cli) and parses its
[report](/cimvocabcheck/report-contract), so a finding it returns is the same finding the editors,
the CI job and any other language binding produce.

```bash
dotnet add package Soptim.CimVocabCheck
```

`net8.0` and `net10.0`, with no package dependencies. It does need an engine to drive — see
[Finding an engine](#finding-an-engine).

## Validating

```csharp
using Soptim.CimVocabCheck;

var report = await Validator.ValidateAsync(
    ["queries/line-segments.rq", "shapes/equipment.ttl"],
    new ValidationOptions { Schema = ["profiles"] });

foreach (var finding in report.Errors())
{
    Console.WriteLine(finding);   // queries/line-segments.rq:3:12: ERROR[UNKNOWN_CLASS] …
}
```

Findings are **data, not exceptions**. A file with errors comes back as a report whose `IsOk()`
is `false`; exceptions are reserved for "the engine could not run, or could not be trusted".

`Validator.Validate(...)` is the blocking form, for a console app or an MSBuild task. It runs the
asynchronous path on the thread pool, so a caller with a synchronization context cannot deadlock.

### One call, every input

Loading a CGMES profile set costs about a second, and validating one more query after that costs
almost nothing — so `ValidateAsync` takes a collection and returns one report:

```csharp
var report = await Validator.ValidateAsync(
    Directory.EnumerateFiles("queries", "*.rq").Order(),
    new ValidationOptions { Config = "opencgmes.jsonc" });
```

`ValidateTextAsync` validates a query held in memory by passing it on stdin; because the engine
tells SHACL from SPARQL by file suffix, shapes have to come from a file.

### Options

```csharp
new ValidationOptions
{
    Schema = ["profiles"],                           // RDFS file(s) or directories; or …
    Config = "opencgmes.jsonc",                      // … a config file; or …
    Endpoint = "http://localhost:3030/ds/query",     // … a SPARQL endpoint
    Profiles = ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
    Strictness = Strictness.Strict,                  // permissive | default | strict | pedantic
    Verbose = true,                                  // WARN/INFO included by default
    WorkingDirectory = "…",
    Timeout = TimeSpan.FromMinutes(2),
}
```

With no `Schema`, `Config` or `Endpoint` the engine discovers the nearest `opencgmes.jsonc` above
the working directory; without one it checks [syntax only](/cimvocabcheck/configuration).

Pass inputs **relative to `WorkingDirectory`**: they appear verbatim in the report, and CI
annotations only line up with a source tree when they are repository-relative.

`Verbose` defaults to `true`, unlike the CLI. A library hands everything back and lets the caller
filter, rather than discarding findings the caller cannot then recover.

## The result

| | |
| --- | --- |
| `report.IsOk()` | no input has an `ERROR` |
| `report.Summary` | counts, matching exactly what this report contains |
| `report.Results` | one `FileResult` per input, in the order they were passed |
| `report.Errors()` / `Warnings()` / `Infos()` | flat findings, each carrying the file it came from |
| `report.OfCode(...)` / `AtLeast(...)` | the same, narrowed |
| `report.ForFile(path)` | one input's result |
| `report.Tool.Version` | which engine produced this |

**Switch on `Code`, never on `Message`**: messages change between releases, codes do not.

```csharp
var typos = report.OfCode(Code.UnknownClass).Concat(report.OfCode(Code.UnknownProperty));
```

## Finding an engine

`Engine.Discover` searches in this order, and the exception names every place it looked:

| # | Source | |
| --- | --- | --- |
| 1 | `ValidationOptions.Engine` / `EnginePath` | an `Engine`, or a path to a JAR or binary |
| 2 | `$CIMVOCABCHECK_JAR`, then `$CIMVOCABCHECK_BIN` | a fat JAR (run with `java`), or an executable |
| 3 | `cimvocabcheck` on `PATH` | |
| 4 | `docker run ghcr.io/soptim/cimvocabcheck-cli:latest` | last resort |

A JAR engine needs a **JRE 21 or newer**, found via `JAVA_HOME` or `PATH`. The Docker fallback
mounts the working directory at the image's `/work`, so inputs must live inside it — see the
[Python page](/cimvocabcheck/python#finding-an-engine) for the shared details, which are the same
for every binding.

## Exceptions

| Type | When |
| --- | --- |
| `EngineNotFoundException` | nothing to run; the message lists everywhere that was searched |
| `ToolException` | the engine reported a usage/config failure (exit `2`), or failed outright |
| `ReportParseException` | output was not a report of the contract major this package targets |
| `EngineTimeoutException` | `Timeout` elapsed |

`ToolException` carries `Arguments`, `ExitCode`, `StandardOutput` and `StandardError`.

## Versions and compatibility

The package tracks the engine it was generated against (`0.7.x` package ↔ `0.7.x` engine) and
accepts any engine declaring the same [report contract](/cimvocabcheck/report-contract) **major**.
Within a major the contract only ever adds, so the package keeps fields it does not know in each
record's `Extra` dictionary and accepts codes it does not know —
`annotation.IsKnownCode()` tells you which.

The model is **generated** from the published JSON Schema by the generator shared with the Python
and Rust bindings; CI fails when the two have drifted apart.
