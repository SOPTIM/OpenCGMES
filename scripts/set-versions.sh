#!/usr/bin/env bash
# Apply computed versions to all build files.
#
# Usage: set-versions.sh <cimxml-version> [<cimvocabcheck-version>]
#
#   cimxml-version   - Maven version for cimxml (e.g. 1.0.2-SNAPSHOT or 1.0.1)
#   cimvocabcheck-version - Maven version for cimvocabcheck modules (e.g. 0.2.1-SNAPSHOT or 0.2.1)
#                      Omit to skip all cimvocabcheck module versioning.
#
# Gradle (IntelliJ) and npm (VS Code) strip the -SNAPSHOT suffix because those
# toolchains do not use Maven snapshot conventions.

set -euo pipefail

CIMXML_VERSION="${1:?usage: set-versions.sh <cimxml-version> [<cimvocabcheck-version>]}"
CIMVOCABCHECK_VERSION="${2:-}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="mvn -B -ntp"
VERSIONS_PLUGIN="org.codehaus.mojo:versions-maven-plugin:2.21.0"

# --- cimxml pom ---
${MVN} -f "${REPO_ROOT}/cimxml/pom.xml" \
    ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMXML_VERSION}" -DgenerateBackupPoms=false

[[ -z "${CIMVOCABCHECK_VERSION}" ]] && exit 0

# --- cimvocabcheck pom versions ---
for MOD in core cli lsp; do
    ${MVN} -f "${REPO_ROOT}/cimvocabcheck/${MOD}/pom.xml" \
        ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMVOCABCHECK_VERSION}" -DgenerateBackupPoms=false
done
${MVN} -f "${REPO_ROOT}/cimvocabcheck/pom.xml" \
    ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMVOCABCHECK_VERSION}" -DgenerateBackupPoms=false

# --- Cross-module version properties ---
# cimvocabcheck-core's dependency on cimxml
${MVN} -f "${REPO_ROOT}/cimvocabcheck/core/pom.xml" \
    ${VERSIONS_PLUGIN}:set-property -Dproperty=ver.cimxml \
    -DnewVersion="${CIMXML_VERSION}" -DgenerateBackupPoms=false

# cli + lsp dependency on cimvocabcheck-core
for MOD in cli lsp; do
    ${MVN} -f "${REPO_ROOT}/cimvocabcheck/${MOD}/pom.xml" \
        ${VERSIONS_PLUGIN}:set-property -Dproperty=ver.cimvocabcheck-core \
        -DnewVersion="${CIMVOCABCHECK_VERSION}" -DgenerateBackupPoms=false
done

# --- IntelliJ plugin (gradle.properties) ---
# Strip -SNAPSHOT: Gradle/IntelliJ plugin version uses plain X.Y.Z.
CIMVOCABCHECK_PLAIN="${CIMVOCABCHECK_VERSION%-SNAPSHOT}"
sed -i "s/^pluginVersion=.*/pluginVersion=${CIMVOCABCHECK_PLAIN}/" \
    "${REPO_ROOT}/cimnotebook/intellij/gradle.properties"

# --- VS Code extension (package.json) ---
# npm/semver does not use -SNAPSHOT; use plain X.Y.Z for all builds.
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"${CIMVOCABCHECK_PLAIN}\"/" \
    "${REPO_ROOT}/cimnotebook/vscode/package.json"
