#!/usr/bin/env bash
# Generate the CIMcheck supply-chain artifacts for all three distributables:
#
#   cimcheck/sbom/maven/bom.json      CycloneDX SBOM for the Java library/CLI/LSP
#   cimcheck/sbom/maven/THIRD-PARTY.txt   + their shipped dependencies (cimxml, Jena, ...)
#   cimcheck/sbom/vscode/bom.json     CycloneDX SBOM for the VS Code extension's
#   cimcheck/sbom/vscode/THIRD-PARTY.txt  shipped npm deps (vscode-languageclient, ...)
#   cimcheck/sbom/intellij/bom.json   CycloneDX SBOM for the IntelliJ plugin's
#   cimcheck/sbom/intellij/THIRD-PARTY.txt  compile deps (IntelliJ Platform, LSP4IJ)
#
# All files are committed. The cimcheck-ci `sbom` job re-runs this script and
# fails if the regenerated files differ from what is committed (outdated SBOM)
# or if any dependency uses a license outside the open-source allow-list.
#
# Requires: mvn, node/npm, and the IntelliJ Gradle wrapper (Java + Node toolchains).
#
# Usage: scripts/generate-sbom.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SBOM_DIR="${REPO_ROOT}/cimcheck/sbom"
MVN="${MVN:-mvn}"
GRADLE="${GRADLE:-./gradlew}"
CYCLONEDX_NPM="@cyclonedx/cyclonedx-npm@4.2.1"

# Strip the single metadata.timestamp line from a CycloneDX BOM. CycloneDX stamps
# the current time even in reproducible mode, which would churn the committed
# file on every run; everything else in the BOM is already deterministic.
strip_bom_timestamp() {
    python3 - "$1" <<'PY'
import re, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as fh:
    lines = fh.readlines()
ts = re.compile(r'^\s{4}"timestamp"\s*:\s*".*Z",\s*$')
kept = [ln for ln in lines if not ts.match(ln)]
removed = len(lines) - len(kept)
if removed != 1:
    sys.exit(f"{path}: expected to strip exactly 1 timestamp line, stripped {removed}")
with open(path, "w", encoding="utf-8") as fh:
    fh.writelines(kept)
PY
}

# ---------------------------------------------------------------------------
# 1. Maven (cimxml + cimcheck-core/cli/lsp)
# ---------------------------------------------------------------------------
echo ">> [maven] Generating CycloneDX SBOM (aggregate) ..."
# Plugin config (output path, reproducible flags, scopes) lives in the root
# pom.xml. Running from the reactor root lets cimcheck-core resolve the cimxml
# SNAPSHOT from the reactor without a prior `mvn install`.
( cd "${REPO_ROOT}" && "${MVN}" -B -ntp org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom )

echo ">> [maven] Generating THIRD-PARTY attribution + enforcing license allow-list ..."
( cd "${REPO_ROOT}" && "${MVN}" -B -ntp license:aggregate-add-third-party )

strip_bom_timestamp "${SBOM_DIR}/maven/bom.json"

# ---------------------------------------------------------------------------
# 2. VS Code extension (shipped npm dependencies)
# ---------------------------------------------------------------------------
echo ">> [vscode] Generating CycloneDX SBOM (shipped npm deps) ..."
mkdir -p "${SBOM_DIR}/vscode"
# --omit dev: only what esbuild bundles into the VSIX. --package-lock-only:
# resolve from the committed lockfile (no install needed). --output-reproducible:
# no serial number / timestamp.
( cd "${REPO_ROOT}/cimcheck/vscode" && npx --yes "${CYCLONEDX_NPM}" \
    --omit dev --package-lock-only --output-reproducible \
    --output-format JSON --output-file "${SBOM_DIR}/vscode/bom.json" )

echo ">> [vscode] Checking license allow-list + writing attribution ..."
python3 "${SCRIPT_DIR}/check-sbom-licenses.py" \
    --bom "${SBOM_DIR}/vscode/bom.json" \
    --output "${SBOM_DIR}/vscode/THIRD-PARTY.txt" \
    --name "CIMcheck VS Code extension"

# ---------------------------------------------------------------------------
# 3. IntelliJ plugin (compile-time IntelliJ Platform libraries + LSP4IJ)
# ---------------------------------------------------------------------------
echo ">> [intellij] Generating CycloneDX SBOM (compileClasspath) ..."
# Task config (scope = compileClasspath, output path) lives in build.gradle.kts.
( cd "${REPO_ROOT}/cimcheck/intellij" && ${GRADLE} cyclonedxBom --no-daemon -q )
strip_bom_timestamp "${SBOM_DIR}/intellij/bom.json"

echo ">> [intellij] Checking license allow-list + writing attribution ..."
python3 "${SCRIPT_DIR}/check-sbom-licenses.py" \
    --bom "${SBOM_DIR}/intellij/bom.json" \
    --output "${SBOM_DIR}/intellij/THIRD-PARTY.txt" \
    --name "CIMcheck IntelliJ plugin"

echo ">> Done. Artifacts under ${SBOM_DIR}:"
find "${SBOM_DIR}" -maxdepth 2 -type f \( -name "bom.json" -o -name "THIRD-PARTY.txt" \) | sort
