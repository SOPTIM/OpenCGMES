# Soptim.CimVocabCheck (.NET)

Validate SPARQL queries and SHACL shapes against CIM/CGMES schema profiles from .NET.

This package is a **binding, not an engine**. The analysis — SPARQL parsing, the algebra walk,
the CGMES profile model, SHACL — is one Apache Jena implementation shared by every consumer, so a
finding you get here is the same finding your editor, your CI job and a colleague's Python script
get. What this package adds is finding that engine, building its command line, and giving you its
published report as typed .NET.

Full documentation: <https://opencgmes.soptim.de/cimvocabcheck/dotnet>

## Install

```bash
dotnet add package Soptim.CimVocabCheck
```

`net8.0` and `net10.0`, with **no package dependencies** — `System.Text.Json` and the BCL already
do everything this needs. You do need an engine to drive; see
[Finding an engine](#finding-an-engine).

## Use it

```csharp
using Soptim.CimVocabCheck;

var report = await Validator.ValidateAsync(
    ["queries/line-segments.rq", "shapes/equipment.ttl"],
    new ValidationOptions { Schema = ["profiles"] });

if (!report.IsOk())
{
    foreach (var finding in report.Errors())
    {
        // queries/line-segments.rq:3:12: ERROR[UNKNOWN_CLASS] Class <…> does not exist …
        Console.WriteLine(finding);
    }
}
```

Findings are **data, not exceptions**: a file with errors comes back as a report whose `IsOk()` is
`false`. Exceptions are reserved for "the engine could not run, or could not be trusted".

`Validator.Validate(...)` is the blocking form, for a console app or an MSBuild task; it runs the
asynchronous path on the thread pool, so a caller with a synchronization context cannot deadlock.

### Validate everything in one call

Loading a CGMES profile set costs about a second; validating one more query after that costs
almost nothing. `ValidateAsync` therefore takes a collection:

```csharp
var report = await Validator.ValidateAsync(
    Directory.EnumerateFiles("queries", "*.rq").Order(),
    new ValidationOptions { Config = "opencgmes.jsonc" });
```

Spawning the engine per file turns a one-second job into a one-second-per-file job.
`ValidateTextAsync` validates a query held in memory by passing it on stdin; because the engine
tells SHACL from SPARQL by file suffix, shapes have to come from a file.

### Reading the report

```csharp
report.IsOk()                   // no input has an ERROR
report.Summary.Errors           // counts, matching exactly what this report contains
report.Results                  // one FileResult per input, in the order they were passed
report.Errors()                 // flat findings, each carrying the file it came from
report.OfCode(Code.UnknownClass)
report.AtLeast(Severity.Warn)
report.ForFile("queries/line-segments.rq")
report.Tool.Version             // which engine produced this
```

Each finding carries `Severity`, `Code`, `Message`, and — when the engine could resolve them —
`Line`, `Column`, `Term`, `Graph` and `FoundInOtherProfiles`.

**Switch on `Code`, never on `Message`.** Messages change between releases; codes do not.

### Options

```csharp
new ValidationOptions
{
    Schema = ["profiles"],                           // RDFS file(s) or directories; or …
    Config = "opencgmes.jsonc",                      // … a config file; or …
    Endpoint = "http://localhost:3030/ds/query",     // … a SPARQL endpoint
    Profiles = ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
    Strictness = Strictness.Strict,
    Verbose = true,                                  // WARN/INFO included by default
    WorkingDirectory = "…",
    Timeout = TimeSpan.FromMinutes(2),
}
```

With no schema, config or endpoint the engine discovers the nearest `opencgmes.jsonc` above the
working directory; without one it checks syntax only.

Pass inputs **relative to `WorkingDirectory`**: they appear verbatim in the report, and CI
annotations only line up with a source tree when they are repository-relative.

`Verbose` defaults to `true`, unlike the CLI. A library hands back everything and lets you
filter, rather than discarding findings you cannot then recover.

## Finding an engine

`Engine.Discover` looks in this order, and the exception names every place it looked:

| # | Source | |
| --- | --- | --- |
| 1 | `ValidationOptions.Engine` / `EnginePath` | an `Engine`, or a path to a JAR or binary |
| 2 | `$CIMVOCABCHECK_JAR`, then `$CIMVOCABCHECK_BIN` | a fat JAR (run with `java`), or an executable |
| 3 | `cimvocabcheck` on `PATH` | |
| 4 | `docker run ghcr.io/soptim/cimvocabcheck-cli:latest` | last resort |

A JAR engine needs a **JRE 21 or newer**, found via `JAVA_HOME` or `PATH`.

The Docker fallback mounts the working directory at the image's `/work`, so inputs must live
inside it — a path outside is refused rather than silently unreadable. `CIMVOCABCHECK_DOCKER_IMAGE`
pins a tag and `CIMVOCABCHECK_NO_DOCKER=1` takes it out of the chain. The first run pulls the
image, and an `Endpoint` on `localhost` is then the *container's* localhost.

## Exceptions

| Type | When |
| --- | --- |
| `EngineNotFoundException` | nothing to run; the message lists everywhere that was searched |
| `ToolException` | the engine reported a usage/config failure (exit `2`), or failed outright |
| `ReportParseException` | output was not a report of the contract major this package targets |
| `EngineTimeoutException` | `Timeout` elapsed |

`ToolException` carries `Arguments`, `ExitCode`, `StandardOutput` and `StandardError`.

## Versioning and compatibility

This package tracks the engine it was generated against (`0.7.x` package ↔ `0.7.x` engine) and
accepts any engine declaring the same **report contract major**. Within a major the contract only
ever adds — new optional fields, new rule codes, new output formats — so the package:

- keeps fields it does not know in each record's `Extra` dictionary rather than rejecting them,
  and
- accepts codes it does not know (`annotation.IsKnownCode()` tells you), because the contract
  requires an unrecognised code to be treated as a generic finding, never as an error.

The contract itself is documented at <https://opencgmes.soptim.de/cimvocabcheck/report-contract>.

## Developing

`Model.g.cs` and `Codes.g.cs` are **generated** from the published JSON Schema — edit the
generator, not the model. Conveniences live in `Ergonomics.cs` and survive regeneration.

```bash
python3 ../codegen/generate_models.py --target csharp           # after a contract change
python3 ../codegen/generate_models.py --target csharp --check   # what CI runs
dotnet test                                                     # engine tests skip without one
CIMVOCABCHECK_JAR=../../cli/target/cimvocabcheck-cli-*.jar dotnet test
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
