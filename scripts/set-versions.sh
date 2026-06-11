#!/usr/bin/env bash
# Apply computed versions to all build files.
#
# Usage: set-versions.sh <cimxml-version> [<cimcheck-version>]
#
#   cimxml-version   - Maven version for cimxml (e.g. 1.0.2-SNAPSHOT or 1.0.1)
#   cimcheck-version - Maven version for cimcheck modules (e.g. 0.2.1-SNAPSHOT or 0.2.1)
#                      Omit to skip all cimcheck module versioning.
#
# Gradle (IntelliJ) and npm (VS Code) strip the -SNAPSHOT suffix because those
# toolchains do not use Maven snapshot conventions.

set -euo pipefail

CIMXML_VERSION="${1:?usage: set-versions.sh <cimxml-version> [<cimcheck-version>]}"
CIMCHECK_VERSION="${2:-}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="mvn -B -ntp"
VERSIONS_PLUGIN="org.codehaus.mojo:versions-maven-plugin:2.21.0"

# --- cimxml pom ---
${MVN} -f "${REPO_ROOT}/cimxml/pom.xml" \
    ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMXML_VERSION}" -DgenerateBackupPoms=false

[[ -z "${CIMCHECK_VERSION}" ]] && exit 0

# --- cimcheck pom versions ---
for MOD in core cli lsp; do
    ${MVN} -f "${REPO_ROOT}/cimcheck/${MOD}/pom.xml" \
        ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMCHECK_VERSION}" -DgenerateBackupPoms=false
done
${MVN} -f "${REPO_ROOT}/cimcheck/pom.xml" \
    ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMCHECK_VERSION}" -DgenerateBackupPoms=false

# --- Cross-module version properties ---
# cimcheck-core's dependency on cimxml
${MVN} -f "${REPO_ROOT}/cimcheck/core/pom.xml" \
    ${VERSIONS_PLUGIN}:set-property -Dproperty=ver.cimxml \
    -DnewVersion="${CIMXML_VERSION}" -DgenerateBackupPoms=false

# cli + lsp dependency on cimcheck-core
for MOD in cli lsp; do
    ${MVN} -f "${REPO_ROOT}/cimcheck/${MOD}/pom.xml" \
        ${VERSIONS_PLUGIN}:set-property -Dproperty=ver.cimcheck-core \
        -DnewVersion="${CIMCHECK_VERSION}" -DgenerateBackupPoms=false
done

# --- IntelliJ plugin (gradle.properties) ---
# Strip -SNAPSHOT: Gradle/IntelliJ plugin version uses plain X.Y.Z.
CIMCHECK_PLAIN="${CIMCHECK_VERSION%-SNAPSHOT}"
sed -i "s/^pluginVersion=.*/pluginVersion=${CIMCHECK_PLAIN}/" \
    "${REPO_ROOT}/cimcheck/intellij/gradle.properties"

# --- VS Code extension (package.json) ---
# npm/semver does not use -SNAPSHOT; use plain X.Y.Z for all builds.
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"${CIMCHECK_PLAIN}\"/" \
    "${REPO_ROOT}/cimcheck/vscode/package.json"
