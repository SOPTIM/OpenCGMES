# CIMNotebook changelog

All notable, user-facing changes to **CIMNotebook** are recorded here. The VS Code extension and
the IntelliJ plugin are one product on one version, so they share this file; an entry that applies
to only one of them says so with a **VS Code:** or **IntelliJ:** prefix.

Both editors bundle the CIMLangServer from a released CIMVocabCheck version — when that server is
what changed, the entry points at [its changelog](../cimvocabcheck/CHANGELOG.md).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Add your entry under _Unreleased_ in the
same pull request as the change; `scripts/release-changelog.sh` turns that section into a released
one at release time, and the release then carries these notes to the GitHub Release, the JetBrains
Marketplace and the VS Code Marketplace.

## [Unreleased]

## [1.1.0] - 2026-09-01

Native, in-editor notebooks. Everything the notebook editor is built on — running a cell against an
endpoint, local files or a named connection — comes from the CIMLangServer this release bundles,
CIMVocabCheck 1.1.0. The editor itself ships in VS Code; in IntelliJ this release is that updated
language server plus dependency updates, and the editor language support is unchanged.

### Added

- **VS Code:** Native Markdown notebook format (`*.cimnb.md`) — a plain, git-friendly Markdown file
  in which top-level `sparql` and `shacl` fenced blocks are executable cells. Any `.md`/`.markdown`
  file can be opened as one through _Open With…_.
- **VS Code:** Interop with Zazuko's _SPARQL Notebook_ `.sparqlbook` format — open and edit it
  directly, or convert a notebook between Markdown and SPARQL Book.
- **VS Code:** Run cells in the editor, with results rendered as a table (SELECT), a verdict (ASK)
  or a Turtle block (CONSTRUCT/DESCRIBE and SHACL reports), and a Stop button to cancel a running
  cell.
- **VS Code:** Cells can target a local file, a glob of files or a named connection from
  `opencgmes.jsonc`, not just a remote endpoint.
- **VS Code:** Connection management — pick or switch a cell's or a notebook's target from the
  status bar. Basic-auth credentials go to VS Code Secret Storage and are never written to the
  config file.
- **VS Code:** A CIMNotebook sidebar for editing `opencgmes.jsonc` — connections, validation
  settings and execution settings, without hand-editing JSON.

### Changed

- The bundled CIMLangServer moves to CIMVocabCheck 1.1.0, which adds the notebook cell execution
  engine behind all of the above.

## [1.0.1] - 2026-07-21

### Fixed

- **IntelliJ:** Ctrl+hover over a CIM term shows its documentation instead of a generic
  _LSP Psi Element_ tooltip.
- **IntelliJ:** A bare `<` comparison operator is no longer highlighted as the start of an IRI.

### Changed

- The bundled CIMLangServer moves to CIMVocabCheck 1.0.1 (maintenance only, no functional change).

## [1.0.0] - 2026-07-07

Initial public release — SPARQL, Turtle and SHACL language support for CIM/CGMES in VS Code and
IntelliJ: syntax highlighting, hover documentation, completion, go to definition and live validation
diagnostics, all served by the bundled CIMLangServer.

### Added

- An `opencgmes.jsonc` config file (plain `opencgmes.json` is still recognised), with a
  `cimNamespaces` option.
- Turtle syntax highlighting for SPARQL embedded in SHACL `sh:select`.
- Refreshed extension icons and logo.
