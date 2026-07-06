---
title: Code Style
sidebar_position: 4
---

# Code Style

OpenCGMES enforces formatting and static analysis on every component, and the CI lint gates fail the build on any violation. This page lists the tools per language and the commands to check and auto-fix locally before you open a pull request. See [CI & releases](/developer-guide/ci-and-releases) for how these gates run in CI.

## Java modules (CIMXML, CIMVocabCheck core/cli/lsp)

The Java modules use a stack of Maven plugins, all wired into the `verify` lifecycle so `mvn verify` runs them:

| Tool | Purpose | Config |
| --- | --- | --- |
| **Spotless** (google-java-format) | Strict source formatting | per-module pom |
| **Checkstyle** | Google style rules | `cimvocabcheck/google_checks.xml` |
| **SpotBugs** | Static bug detection | `cimvocabcheck/spotbugs-excludes.xml` |
| **PMD + CPD** | Static analysis + copy-paste detection | per-module pom |
| **JaCoCo** | Coverage floors (instruction/branch) | per-module pom |

Check formatting and run the full static-analysis suite:

```bash
mvn spotless:check                       # formatting only (fails on unformatted code)
mvn verify                               # Spotless + Checkstyle + SpotBugs + PMD + JaCoCo
```

Auto-fix formatting:

```bash
mvn spotless:apply                       # reformat all modules
mvn -pl cimvocabcheck/core spotless:apply   # reformat a single module
```

:::tip Fast lint feedback
CI runs the lint gates separately from the test suite. You can mirror its fast lint pass — static analysis without tests or coverage — with:

```bash
mvn -pl cimvocabcheck/core,cimvocabcheck/cli,cimvocabcheck/lsp -am \
    verify -DskipTests -Djacoco.skip=true
```
:::

The CIMVocabCheck CI lint job additionally treats **compiler warnings as errors** (unused imports, raw types, deprecation, missing `@Override`), so keep the build warning-clean:

```bash
mvn -pl cimvocabcheck/core,cimvocabcheck/cli,cimvocabcheck/lsp -am \
    clean compile test-compile \
    -Dmaven.compiler.failOnWarning=true
```

## VS Code extension (TypeScript)

The VS Code extension uses Prettier for formatting and ESLint for linting, run via npm scripts from `cimnotebook/vscode`:

```bash
cd cimnotebook/vscode
npm run lint            # ESLint
npm run format:check    # Prettier — fails on unformatted code
npm run compile         # TypeScript type-check
```

These three are exactly what the `typecheck-vscode` CI job runs. (Use your editor's Prettier integration, or `npx prettier --write .`, to auto-format.)

## IntelliJ plugin (Kotlin)

The IntelliJ plugin uses Spotless with **ktlint** (official Kotlin style), run via the Gradle wrapper from `cimnotebook/intellij`:

```bash
cd cimnotebook/intellij
./gradlew spotlessCheck   # verify Kotlin formatting/lint (also wired into `check`)
./gradlew spotlessApply   # auto-format Kotlin
```

The CI `build-intellij-plugin` job runs `gradle spotlessCheck` before `gradle buildPlugin`, mirroring the Maven Spotless gate.

## License headers

Every first-party source file carries a standard SOPTIM Apache-2.0 header (with an
`SPDX-License-Identifier: Apache-2.0` line), and CI fails the build if a file is missing it or has
it wrong. Check or fix all of it in one shot:

```bash
scripts/license-headers.sh check    # fails if any header is missing/wrong (default)
scripts/license-headers.sh format   # inserts/rewrites headers in place
```

This script drives the same three gates CI uses:

- **Maven (root + CIMVocabCheck)** — the `mycila` `license-maven-plugin` checks `pom.xml` files, and
  Spotless (google-java-format) checks/formats the header on `src/**/*.java`. Enforced for
  `cimvocabcheck/core`, `cimvocabcheck/cli`, and `cimvocabcheck/lsp`. **CIMXML is exempt** — the
  plugin is wired up with the header but skipped by default (`cimxml.license.skip=true`); CI runs it
  advisory-only there, so it will not fail your build.
- **IntelliJ plugin** — Spotless `licenseHeader` for Kotlin, `*.gradle.kts`, `plugin.xml`, and
  README, riding the existing `./gradlew spotlessCheck`.
- **VS Code extension** — a local auto-fixable ESLint rule (`soptim/copyright-header`), riding the
  existing `npm run lint`.

The IDE side is covered too: the shared `.idea/copyright/` profile makes IntelliJ auto-insert the
same header on new files, so you rarely need to run `format` by hand for Java/Kotlin.

## Before you push

Run the relevant gate for whatever you touched:

- **Java change** → `mvn verify` (or `mvn spotless:apply` then `mvn verify`).
- **VS Code change** → `npm run lint && npm run format:check && npm run compile`.
- **IntelliJ change** → `./gradlew spotlessCheck buildPlugin`.
- **New file, any language** → `scripts/license-headers.sh check` (or `format` to fix).

If you changed any dependency, also regenerate the SBOMs — see [CI & releases → Supply chain](/developer-guide/ci-and-releases#supply-chain-sbom--licenses).
