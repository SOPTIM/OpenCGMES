#!/usr/bin/env bash
# Compute the Maven version for a component from git state.
#
# Usage: compute-version.sh <component>
#   component: cimxml | cimvocabcheck | cimnotebook
#
# On a tagged release push (GITHUB_REF_TYPE=tag, GITHUB_REF_NAME=<component>-vX.Y.Z):
#   prints X.Y.Z
#
# On any other ref (branches, local):
#   finds the last <component>-v* tag reachable from HEAD, bumps patch +1,
#   appends -SNAPSHOT.  Falls back to 0.0.0-SNAPSHOT if no tag exists.

set -euo pipefail

COMPONENT="${1:?usage: compute-version.sh <component>}"

if [[ "${GITHUB_REF_TYPE:-}" == "tag" ]]; then
    TAG="${GITHUB_REF_NAME:-}"
    if [[ "${TAG}" =~ ^${COMPONENT}-v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
        exit 0
    fi
fi

LAST_TAG="$(git tag --merged HEAD --sort=-version:refname --list "${COMPONENT}-v*" | head -1)"
if [[ -z "${LAST_TAG}" ]]; then
    echo "0.0.0-SNAPSHOT"
    exit 0
fi

if [[ "${LAST_TAG}" =~ ^${COMPONENT}-v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))-SNAPSHOT"
else
    echo "0.0.0-SNAPSHOT"
fi
