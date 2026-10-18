# Language bindings

Non-JVM consumers of CIMVocabCheck. Each one drives the [CLI](../cli) and parses its
[published report](../schemas/cimvocabcheck-report-1.schema.json); none of them re-implements any
analysis. There is one engine, and these are transports to it.

| Directory | Package | Docs |
| --- | --- | --- |
| [`python/`](python) | `cimvocabcheck` (PyPI) | <https://opencgmes.soptim.de/cimvocabcheck/python> |
| [`dotnet/`](dotnet) | `Soptim.CimVocabCheck` (NuGet) | <https://opencgmes.soptim.de/cimvocabcheck/dotnet> |
| [`rust/`](rust) | `cimvocabcheck` (crates.io) | <https://opencgmes.soptim.de/cimvocabcheck/rust> |

## One contract, one generator

Each binding's result model is generated from the published JSON Schema by
[`codegen/generate_models.py`](codegen), which reads the schema once into a language-neutral
shape and hands it to one emitter per language. That is the point: how the contract maps onto a
typed model is decided in a single place, so the three packages cannot drift apart. A fourth
language is an emitter, not another reading of the schema.

```bash
python3 codegen/generate_models.py                       # regenerate all three
python3 codegen/generate_models.py --target rust         # or just one
python3 codegen/generate_models.py --check               # what CI runs
```

The generated files are committed, so building or consuming any of the three packages never runs
Python. Everything that is *convenience* rather than *contract* lives in a hand-written
neighbour (`_ergonomics.py`, `ergonomics.rs`, `Ergonomics.cs`) that survives regeneration.

## What every binding does the same way

These are the decisions the contract forces, so all three make them identically:

- **Batch first.** Loading a CGMES profile set costs about a second and an extra query costs
  almost nothing, so the primary call takes a list of inputs and returns one report.
- **Findings are data.** Only "the engine could not run, or could not be trusted" raises.
  Exit code `1` is a report; exit code `2` is a failure.
- **argv, never a shell string.** Paths contain spaces.
- **Rule codes are constants, not a closed enum.** The contract adds codes in minor versions and
  obliges consumers to tolerate one they do not know; an enum is precisely the shape that
  cannot.
- **Unknown fields are kept**, in an `extra` map, rather than dropped.
- **`verbose` defaults to on**, unlike the CLI: a library hands everything back and lets the
  caller filter.
- **The same engine discovery order**, with a failure message naming every place that was
  searched: explicit → environment → `PATH` → Docker.

## Testing against a real engine

Each suite's engine-backed tests skip when no engine is discoverable, so the suites are
meaningful on a machine with no JRE and authoritative on one with a built CLI. CI sets
`CIMVOCABCHECK_TESTS_REQUIRE_ENGINE=1`, which turns that skip into a failure, and points
`CIMVOCABCHECK_JAR` at a freshly built fat JAR.

Note that discovery can otherwise fall through to `ghcr.io/soptim/cimvocabcheck-cli:latest`,
which is the *published* image and therefore lags this checkout; the suites probe the engine and
skip rather than reporting a wall of contract failures.
