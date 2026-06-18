# CIMVocabCheck supply-chain artifacts

This directory holds the committed Software Bill of Materials (SBOM) and the
third-party license attribution for the **CIMVocabCheck** distributables (the Java
library, CLI and language server). The CIMNotebook editor plugins keep their own
SBOMs under [`cimnotebook/sbom/`](../../cimnotebook/sbom/). Every file here is
generated, committed, and verified in CI.

| Path                    | Distributable            | Tool                   | Covers                                                                             |
| ----------------------- | ------------------------ | ---------------------- | ---------------------------------------------------------------------------------- |
| `maven/bom.json`        | Java library / CLI / LSP | CycloneDX Maven plugin | `cimxml` + `cimvocabcheck-core`/`cli`/`lsp` and all shipped (compile+runtime) deps |
| `maven/THIRD-PARTY.txt` | "                        | license-maven-plugin   | attribution for the above                                                          |

All BOMs are [CycloneDX](https://cyclonedx.org/) 1.6 JSON.

## Regenerating

```bash
scripts/generate-sbom.sh maven        # requires mvn
```

The script regenerates every file in place and then **canonicalizes** each
`bom.json` so the committed copy is byte-identical no matter which machine
generated it (the CI `sbom` job re-runs the script and fails on any drift). The
canonicalization strips/normalizes the values the CycloneDX toolchain derives
from the local environment, which would otherwise churn the files:

- the `metadata.timestamp` build stamp;
- the git remote URL form (an `ssh://git@github.com:…` checkout is rewritten to
  the canonical `https://github.com/…`, matching CI's https checkout);
- the generating `npm` version recorded by `cyclonedx-npm`;
- component / dependency ordering (sorted deterministically);
- the per-file hashes in the IntelliJ plugin's `bom.json` (under
  [`cimnotebook/sbom/`](../../cimnotebook/sbom/)) — the IntelliJ Platform Gradle
  plugin hashes the **bytecode-instrumented** platform jars
  (`instrumented-lsp4ij-*.jar`, …), and the instrumenter output is not
  reproducible across JDK/IDE builds. These cover compile-only, non-shipped
  artifacts, so the hashes are dropped (component, version and license — what
  the license gate needs — are kept).

Running `scripts/generate-sbom.sh` with no args regenerates all three components
across both sbom directories. With the above normalized, re-running with
unchanged dependencies produces byte-identical files on any machine.

**Whenever you change a Maven dependency** — a version in any `pom.xml` — re-run the
script and commit the updated files in the same change.

## CI enforcement (`cimvocabcheck-ci` → `sbom` job)

The job re-runs `scripts/generate-sbom.sh maven` (Java toolchain) and:

1. **License gate** — fails if any dependency uses a license that is **not** on
   the reviewed open-source allow-list, or has no detectable license.
2. **Drift check** — `git diff --exit-code -- cimvocabcheck/sbom`. Fails if the
   committed files no longer match the current dependency set. Fix by running
   the script and committing the result.

## License allow-list

All shipped/built-against dependencies must use a reviewed open-source license:

`Apache-2.0`, `MIT`, `ISC`, `BSD-2-Clause`, `BSD-3-Clause`, `EPL-1.0`, `EPL-2.0`,
`GPL-2.0-with-classpath-exception` (OpenJDK-style; `org.glassfish:jakarta.json`,
dual-licensed with EPL-2.0).

The allow-list lives in two places, kept in sync:

- **Maven**: `<includedLicenses>` + `<licenseMerges>` under the
  `license-maven-plugin` config in the root `pom.xml`.
- **npm + Gradle** (CIMNotebook plugins): `ALLOWED` / `MERGES` in
  `scripts/check-sbom-licenses.py`.
