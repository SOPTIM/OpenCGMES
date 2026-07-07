# CIMNotebook supply-chain artifacts

This directory holds the committed Software Bill of Materials (SBOM) and the
third-party license attribution for the **CIMNotebook** editor plugins (the VS Code
extension and the IntelliJ plugin). The CIMVocabCheck library/CLI/LSP keep their own
Maven SBOM under [`cimvocabcheck/sbom/`](../../cimvocabcheck/sbom/). Every file here is
generated, committed, and verified in CI.

> These SBOMs cover only what each plugin **builds against / ships of its own**. The
> bundled CIMVocabCheck language server (`cimvocabcheck-lsp.jar`) is a first-party
> artifact whose own dependencies are inventoried in `cimvocabcheck/sbom/maven`.

| Path                       | Distributable     | Tool                             | Covers                                                             |
| -------------------------- | ----------------- | -------------------------------- | ----------------------------------------------------------------- |
| `vscode/bom.json`          | VS Code extension | `@cyclonedx/cyclonedx-npm`       | shipped npm deps (`vscode-languageclient` and its transitive deps) |
| `vscode/THIRD-PARTY.txt`   | "                 | `scripts/check-sbom-licenses.py` | attribution for the above                                          |
| `intellij/bom.json`        | IntelliJ plugin   | CycloneDX Gradle plugin          | `compileClasspath`: the IntelliJ Platform (2024.2) + LSP4IJ        |
| `intellij/THIRD-PARTY.txt` | "                 | `scripts/check-sbom-licenses.py` | attribution for the above                                          |

All BOMs are [CycloneDX](https://cyclonedx.org/) 1.6 JSON.

## Regenerating

```bash
scripts/generate-sbom.sh vscode intellij   # requires node/npm + the Gradle wrapper
```

(`scripts/generate-sbom.sh` with no args regenerates all three components across both
sbom directories.) Output is deterministic, so re-running with unchanged dependencies
produces byte-identical files.

**Whenever you change a dependency** — `cimnotebook/vscode/package.json` /
`package-lock.json`, or the `platformVersion`/`lsp4ijVersion` in
`cimnotebook/intellij/gradle.properties` — re-run the script and commit the updated
files in the same change.

## CI enforcement (`cimnotebook-ci` → `sbom` job)

The job re-runs `scripts/generate-sbom.sh vscode intellij` (Node + Gradle toolchains) and:

1. **License gate** — fails if any dependency uses a license that is **not** on
   the reviewed open-source allow-list, or has no detectable license.
2. **Drift check** — `git diff --exit-code -- cimnotebook/sbom`. Fails if the
   committed files no longer match the current dependency set.

## License allow-list

The allow-list (`ALLOWED` / `MERGES` in `scripts/check-sbom-licenses.py`) is shared with
the Maven side. Some components ship no per-artifact license metadata (notably the
IntelliJ Platform jars and LSP4IJ); their licenses are **asserted** from the upstream
project's published LICENSE in `scripts/sbom-license-overrides.json` (`idea:ideaIC` →
Apache-2.0; LSP4IJ → EPL-2.0). Adding a dependency under any other license makes CI fail
until the license is reviewed and, if acceptable, added to the allow-list.
