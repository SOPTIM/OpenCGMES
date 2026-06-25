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

# Canonicalize a CycloneDX BOM so the committed copy is byte-identical regardless
# of which machine generated it. The CycloneDX toolchain leaks a handful of
# environment- and machine-specific values that would otherwise churn the committed
# files and break the CI drift check:
#
#   * metadata.timestamp     — stamped even in reproducible mode.
#   * git remote URL form    — the Gradle plugin reads the local `origin` remote, so
#                              an ssh checkout yields ssh://git@github.com:… while CI's
#                              https checkout yields https://github.com/… .
#   * generating npm version — cyclonedx-npm records the host npm version (e.g. dev's
#                              npm 11 vs CI's npm 10).
#   * component ordering     — the Maven aggregate BOM's component/dependency order is
#                              not stable across machines.
#   * IntelliJ jar hashes    — the IntelliJ Platform Gradle plugin hashes the *bytecode-
#                              instrumented* platform jars (instrumented-lsp4ij-*.jar, …);
#                              the instrumenter output is not reproducible across JDK/IDE
#                              builds, so these hashes differ per machine. They cover
#                              compile-only, non-shipped artifacts, so we drop them.
#
# $1 = path to bom.json; pass "intellij" as $2 to also drop the non-reproducible hashes.
canonicalize_bom() {
    python3 - "$1" "${2:-}" <<'PY'
import json, re, sys

path = sys.argv[1]
strip_hashes = len(sys.argv) > 2 and sys.argv[2] == "intellij"

text = open(path, encoding="utf-8").read()
data = json.loads(text)

# Preserve whichever colon spacing the generating tool already uses (CycloneDX
# Maven/Gradle emit " : ", cyclonedx-npm emits ": ") so the reformat stays minimal.
sep = (",", " : ") if '"specVersion" : ' in text else (",", ": ")

GITHUB_SSH = re.compile(r'^(?:ssh://)?git@github\.com[:/](?P<p>.+?)(?:\.git)?/?$')

def walk(node):
    if isinstance(node, dict):
        url = node.get("url")
        if node.get("type") == "vcs" and isinstance(url, str):
            m = GITHUB_SSH.match(url)
            if m:
                node["url"] = "https://github.com/" + m.group("p")
        if strip_hashes:
            node.pop("hashes", None)
        for v in node.values():
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)

walk(data)

meta = data.get("metadata", {})
meta.pop("timestamp", None)
for tool in (meta.get("tools", {}).get("components") or []):
    if tool.get("name") == "npm":
        tool.pop("version", None)

def component_key(c):
    return (c.get("bom-ref") or c.get("purl")
            or f"{c.get('group','')}:{c.get('name','')}:{c.get('version','')}")

if isinstance(data.get("components"), list):
    data["components"].sort(key=component_key)
if isinstance(data.get("dependencies"), list):
    for dep in data["dependencies"]:
        if isinstance(dep.get("dependsOn"), list):
            dep["dependsOn"].sort()
    data["dependencies"].sort(key=lambda d: d.get("ref", ""))

with open(path, "w", encoding="utf-8") as fh:
    fh.write(json.dumps(data, indent=2, separators=sep, ensure_ascii=False))
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

canonicalize_bom "${SBOM_DIR}/maven/bom.json"

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

canonicalize_bom "${SBOM_DIR}/vscode/bom.json"

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
canonicalize_bom "${SBOM_DIR}/intellij/bom.json" intellij

echo ">> [intellij] Checking license allow-list + writing attribution ..."
python3 "${SCRIPT_DIR}/check-sbom-licenses.py" \
    --bom "${SBOM_DIR}/intellij/bom.json" \
    --output "${SBOM_DIR}/intellij/THIRD-PARTY.txt" \
    --name "CIMcheck IntelliJ plugin"

echo ">> Done. Artifacts under ${SBOM_DIR}:"
find "${SBOM_DIR}" -maxdepth 2 -type f \( -name "bom.json" -o -name "THIRD-PARTY.txt" \) | sort
