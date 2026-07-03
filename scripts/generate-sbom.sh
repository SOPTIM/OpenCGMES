#!/usr/bin/env bash
# Generate the OpenCGMES supply-chain artifacts (CycloneDX SBOM + THIRD-PARTY attribution).
#
# Usage: scripts/generate-sbom.sh [maven] [vscode] [intellij]   (no args = all three)
#
#   maven    -> cimvocabcheck/sbom/maven/{bom.json,THIRD-PARTY.txt}
#               Java library/CLI/LSP: cimxml + cimvocabcheck-core/cli/lsp and shipped deps.
#   vscode   -> cimnotebook/sbom/vscode/{bom.json,THIRD-PARTY.txt}
#               VS Code extension shipped npm deps (vscode-languageclient, ...).
#   intellij -> cimnotebook/sbom/intellij/{bom.json,THIRD-PARTY.txt}
#               IntelliJ plugin compile deps (IntelliJ Platform, LSP4IJ).
#
# Selecting a subset lets the independent cimvocabcheck / cimnotebook CI workflows each
# regenerate and drift-check only the artifacts they own. All files are committed; CI
# fails if a regeneration differs from what is committed or uses a non-allow-listed license.
#
# Requires (per selected component): mvn (maven), node/npm (vscode), Gradle wrapper (intellij).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

VOCAB_SBOM_DIR="${REPO_ROOT}/cimvocabcheck/sbom"   # maven (CIMVocabCheck)
NB_SBOM_DIR="${REPO_ROOT}/cimnotebook/sbom"        # vscode + intellij (CIMNotebook)
MVN="${MVN:-mvn}"
GRADLE="${GRADLE:-./gradlew}"
CYCLONEDX_NPM="@cyclonedx/cyclonedx-npm@4.2.1"

# Which components to (re)generate — default to all.
if [[ $# -eq 0 ]]; then
    COMPONENTS=(maven vscode intellij)
else
    COMPONENTS=("$@")
fi
want() {
    local c
    for c in "${COMPONENTS[@]}"; do [[ "${c}" == "$1" ]] && return 0; done
    return 1
}

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
# 1. Maven (cimxml + cimvocabcheck-core/cli/lsp)  ->  cimvocabcheck/sbom/maven
# ---------------------------------------------------------------------------
if want maven; then
    echo ">> [maven] Generating CycloneDX SBOM (aggregate) ..."
    # Plugin config (output path, reproducible flags, scopes) lives in the root
    # pom.xml. Running from the reactor root lets cimvocabcheck-core resolve the cimxml
    # SNAPSHOT from the reactor without a prior `mvn install`.
    ( cd "${REPO_ROOT}" && "${MVN}" -B -ntp org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom )

    echo ">> [maven] Generating THIRD-PARTY attribution + enforcing license allow-list ..."
    ( cd "${REPO_ROOT}" && "${MVN}" -B -ntp org.codehaus.mojo:license-maven-plugin:aggregate-add-third-party )

    canonicalize_bom "${VOCAB_SBOM_DIR}/maven/bom.json"
fi

# ---------------------------------------------------------------------------
# 2. VS Code extension (shipped npm dependencies)  ->  cimnotebook/sbom/vscode
# ---------------------------------------------------------------------------
if want vscode; then
    echo ">> [vscode] Generating CycloneDX SBOM (shipped npm deps) ..."
    mkdir -p "${NB_SBOM_DIR}/vscode"
    # --omit dev: only what esbuild bundles into the VSIX. --package-lock-only:
    # resolve from the committed lockfile (no install needed). --output-reproducible:
    # no serial number / timestamp.
    ( cd "${REPO_ROOT}/cimnotebook/vscode" && npx --yes "${CYCLONEDX_NPM}" \
        --omit dev --package-lock-only --output-reproducible \
        --output-format JSON --output-file "${NB_SBOM_DIR}/vscode/bom.json" )

    canonicalize_bom "${NB_SBOM_DIR}/vscode/bom.json"

    echo ">> [vscode] Checking license allow-list + writing attribution ..."
    python3 "${SCRIPT_DIR}/check-sbom-licenses.py" \
        --bom "${NB_SBOM_DIR}/vscode/bom.json" \
        --output "${NB_SBOM_DIR}/vscode/THIRD-PARTY.txt" \
        --name "CIMNotebook VS Code extension"
fi

# ---------------------------------------------------------------------------
# 3. IntelliJ plugin (compile-time IntelliJ Platform libraries + LSP4IJ)
#    ->  cimnotebook/sbom/intellij  (cyclonedxBom destination set in build.gradle.kts)
# ---------------------------------------------------------------------------
if want intellij; then
    echo ">> [intellij] Generating CycloneDX SBOM (compileClasspath) ..."
    # Task config (scope = compileClasspath, output path) lives in build.gradle.kts.
    ( cd "${REPO_ROOT}/cimnotebook/intellij" && ${GRADLE} cyclonedxBom --no-daemon -q )
    canonicalize_bom "${NB_SBOM_DIR}/intellij/bom.json" intellij

    echo ">> [intellij] Checking license allow-list + writing attribution ..."
    python3 "${SCRIPT_DIR}/check-sbom-licenses.py" \
        --bom "${NB_SBOM_DIR}/intellij/bom.json" \
        --output "${NB_SBOM_DIR}/intellij/THIRD-PARTY.txt" \
        --name "CIMNotebook IntelliJ plugin"
fi

echo ">> Done. Generated: ${COMPONENTS[*]}"
