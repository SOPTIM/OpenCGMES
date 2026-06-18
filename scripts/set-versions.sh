#!/usr/bin/env bash
# Apply computed versions to all build files.
#
# Usage: set-versions.sh <cimxml-version> [<cimvocabcheck-version>] [<cimnotebook-version>]
#
#   cimxml-version        - Maven version for cimxml (e.g. 1.0.2-SNAPSHOT or 1.0.1)
#   cimvocabcheck-version - Maven version for the cimvocabcheck modules: core / cli / lsp
#                           (e.g. 0.2.1-SNAPSHOT or 0.2.1). Omit to skip them.
#   cimnotebook-version   - Version for the cimnotebook editor plugins: the IntelliJ plugin
#                           (gradle.properties) and the VS Code extension (package.json).
#                           Omit to skip them.
#
# The three trains are independent: cimvocabcheck (core/cli/lsp) and cimnotebook
# (intellij/vscode) are versioned separately. A cimnotebook build still needs a
# cimvocabcheck-version because it bundles the cimvocabcheck LSP fat JAR.
#
# Gradle (IntelliJ) and npm (VS Code) strip the -SNAPSHOT suffix because those
# toolchains do not use Maven snapshot conventions.

set -euo pipefail

CIMXML_VERSION="${1:?usage: set-versions.sh <cimxml-version> [<cimvocabcheck-version>] [<cimnotebook-version>]}"
CIMVOCABCHECK_VERSION="${2:-}"
CIMNOTEBOOK_VERSION="${3:-}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="mvn -B -ntp"
VERSIONS_PLUGIN="org.codehaus.mojo:versions-maven-plugin:2.21.0"

# --- cimxml pom ---
${MVN} -f "${REPO_ROOT}/cimxml/pom.xml" \
    ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMXML_VERSION}" -DgenerateBackupPoms=false

# --- cimvocabcheck pom versions (core / cli / lsp + reactor) ---
if [[ -n "${CIMVOCABCHECK_VERSION}" ]]; then
    for MOD in core cli lsp; do
        ${MVN} -f "${REPO_ROOT}/cimvocabcheck/${MOD}/pom.xml" \
            ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMVOCABCHECK_VERSION}" -DgenerateBackupPoms=false
    done
    ${MVN} -f "${REPO_ROOT}/cimvocabcheck/pom.xml" \
        ${VERSIONS_PLUGIN}:set -DnewVersion="${CIMVOCABCHECK_VERSION}" -DgenerateBackupPoms=false

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
fi

# --- cimnotebook plugin versions (IntelliJ + VS Code) ---
if [[ -n "${CIMNOTEBOOK_VERSION}" ]]; then
    # Strip -SNAPSHOT: Gradle/IntelliJ + npm/semver use plain X.Y.Z.
    CIMNOTEBOOK_PLAIN="${CIMNOTEBOOK_VERSION%-SNAPSHOT}"

    # IntelliJ plugin (gradle.properties)
    sed -i "s/^pluginVersion=.*/pluginVersion=${CIMNOTEBOOK_PLAIN}/" \
        "${REPO_ROOT}/cimnotebook/intellij/gradle.properties"

    # VS Code extension (package.json)
    sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"${CIMNOTEBOOK_PLAIN}\"/" \
        "${REPO_ROOT}/cimnotebook/vscode/package.json"
fi
