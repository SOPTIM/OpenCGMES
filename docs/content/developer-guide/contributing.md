---
title: Contributing
sidebar_position: 6
---

# Contributing

Contributions to OpenCGMES are welcome — bug reports, fixes, new validation checks, documentation, and CGMES profile support. This page covers the practical workflow: where code lives, what to run before opening a pull request, and the project's conduct and licensing policies. For deeper setup detail, see [Building](/developer-guide/building), [Testing](/developer-guide/testing), and [Code style](/developer-guide/code-style).

## Workflow

1. **Fork** the [SOPTIM/OpenCGMES](https://github.com/SOPTIM/OpenCGMES) repository and clone your fork.
2. **Initialise the submodule** if you'll touch CIMVocabCheck or run the integration tests:
   ```bash
   git submodule update --init
   ```
3. **Create a branch** for your change off `main`.
4. **Make your change** in the right module — see [Where code lives](#where-code-lives) below.
5. **Run the checks** for whatever you touched (tests + lint + SBOM — see below).
6. **Open a pull request** against `main`. CI runs the matching `-ci` workflow automatically; keep it green.

## Where code lives

The [Repository Overview](/developer-guide/overview) has the full module map. In short:

| If you're changing… | Work in… |
| --- | --- |
| CIMXML parsing / profiles | `cimxml/` |
| Validation logic (checks, schema index, API) | `cimvocabcheck/core/` |
| The CLI tool | `cimvocabcheck/cli/` |
| The language server | `cimvocabcheck/lsp/` |
| The VS Code extension | `cimnotebook/vscode/` |
| The IntelliJ plugin | `cimnotebook/intellij/` |

## Before you open a PR

Run the gates relevant to your change — these mirror the CI workflows, so passing them locally means CI is likely to pass:

```bash
# Java change (CIMXML / CIMVocabCheck)
mvn spotless:apply        # auto-format
mvn verify                # tests + Checkstyle/SpotBugs/PMD/JaCoCo

# VS Code change
cd cimnotebook/vscode && npm run lint && npm run format:check && npm run compile

# IntelliJ change
cd cimnotebook/intellij && ./gradlew spotlessCheck buildPlugin
```

:::warning Regenerate SBOMs when dependencies change
If you add, remove, or bump any dependency, re-run `scripts/generate-sbom.sh` (the relevant subset) and commit the updated SBOM/attribution files in the same change. CI fails on SBOM drift. See [CI & releases → Supply chain](/developer-guide/ci-and-releases#supply-chain-sbom--licenses).
:::

:::tip Don't worry about versions
Versions are derived from Git tags by the [versioning scripts](/developer-guide/ci-and-releases#versioning) at build/release time — do not bump versions by hand in your PR.
:::

## Tests

Add or update tests alongside your change. Unit tests run on any checkout; the ENTSO-E **integration tests** (CGMES 2.4.15 + 3.0) need the submodule and are skipped automatically when it's absent. See [Testing](/developer-guide/testing) for what's covered and how to run the full suite.

## Code of Conduct

This project adheres to a Code of Conduct adapted from the [Apache Foundation's Code of Conduct](https://www.apache.org/foundation/policies/conduct). All contributors and users are expected to follow it, to keep the community welcoming and inclusive.

## License

OpenCGMES is licensed under the [Apache License 2.0](https://github.com/SOPTIM/OpenCGMES/blob/main/LICENSE). By contributing, you agree that your contributions are licensed under the same terms. New dependencies must use a license on the project's reviewed open-source allow-list (Apache-2.0, MIT, BSD-2/3-Clause, EPL-1.0/2.0, and a few others) — the CI license gate enforces this.

## Getting help

- **Issues & feature requests** — [OpenCGMES GitHub Issues](https://github.com/SOPTIM/OpenCGMES/issues).
- **Commercial support** — maintenance, integration, and custom extensions are available from **[SOPTIM AG](https://www.soptim.de/)**; contact [opencgmes@soptim.de](mailto:opencgmes@soptim.de).
