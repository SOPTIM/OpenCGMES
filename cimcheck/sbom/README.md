# CIMcheck supply-chain artifacts

This directory holds the committed Software Bill of Materials (SBOM) and the
third-party license attribution for **all three CIMcheck distributables**. Every
file here is generated, committed, and verified in CI.

| Path                          | Distributable           | Tool                          | Covers                                                              |
| ----------------------------- | ----------------------- | ----------------------------- | ------------------------------------------------------------------- |
| `maven/bom.json`              | Java library / CLI / LSP | CycloneDX Maven plugin        | `cimxml` + `cimcheck-core`/`cli`/`lsp` and all shipped (compile+runtime) deps |
| `maven/THIRD-PARTY.txt`       | "                       | license-maven-plugin          | attribution for the above                                           |
| `vscode/bom.json`             | VS Code extension       | `@cyclonedx/cyclonedx-npm`    | shipped npm deps (`vscode-languageclient` and its transitive deps)  |
| `vscode/THIRD-PARTY.txt`      | "                       | `scripts/check-sbom-licenses.py` | attribution for the above                                        |
| `intellij/bom.json`           | IntelliJ plugin         | CycloneDX Gradle plugin       | `compileClasspath`: the IntelliJ Platform (2024.2) + LSP4IJ         |
| `intellij/THIRD-PARTY.txt`    | "                       | `scripts/check-sbom-licenses.py` | attribution for the above                                        |

All BOMs are [CycloneDX](https://cyclonedx.org/) 1.6 JSON.

## Regenerating

```bash
scripts/generate-sbom.sh        # requires mvn, node/npm and the Gradle wrapper
```

The script regenerates every file in place. Output is deterministic (stable
component ordering; serial numbers and build timestamps are stripped/disabled),
so re-running with unchanged dependencies produces byte-identical files.

**Whenever you change a dependency** — a version in any `pom.xml`,
`cimcheck/vscode/package.json` / `package-lock.json`, or the
`platformVersion`/`lsp4ijVersion` in `cimcheck/intellij/gradle.properties` —
re-run the script and commit the updated files in the same change.

## CI enforcement (`cimcheck-ci` → `sbom` job)

The job re-runs `scripts/generate-sbom.sh` (Java + Node + Gradle toolchains) and:

1. **License gate** — fails if any dependency uses a license that is **not** on
   the reviewed open-source allow-list, or has no detectable license.
2. **Drift check** — `git diff --exit-code -- cimcheck/sbom`. Fails if the
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
- **npm + Gradle**: `ALLOWED` / `MERGES` in `scripts/check-sbom-licenses.py`.

Some components ship no per-artifact license metadata (notably the IntelliJ
Platform jars and LSP4IJ). Their licenses are **asserted** from the upstream
project's published LICENSE in `scripts/sbom-license-overrides.json`
(`idea:ideaIC` → Apache-2.0; LSP4IJ → EPL-2.0). Adding a dependency under any
other license makes CI fail until the license is reviewed and, if acceptable,
added to the allow-list.
