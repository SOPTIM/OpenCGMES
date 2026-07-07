#!/usr/bin/env bash
# Compute the Maven version for a component from git state.
#
# Usage: compute-version.sh <component> [--released]
#   component: cimxml | cimvocabcheck | cimnotebook
#
# Default mode (for building/publishing):
#   On a tagged release push (GITHUB_REF_TYPE=tag, GITHUB_REF_NAME=<component>-vX.Y.Z):
#     prints X.Y.Z
#   On any other ref (branches, local):
#     finds the last <component>-v* tag reachable from HEAD, bumps patch +1,
#     appends -SNAPSHOT.  Falls back to 0.0.0-SNAPSHOT if no tag exists.
#
# --released mode (for documentation / "latest version users can depend on"):
#   prints the newest released <component>-v* tag that exists in the repo, as-is (X.Y.Z, no bump,
#   no -SNAPSHOT).  This is the version published to Maven Central — a global fact, so it is NOT
#   filtered by reachability from HEAD (a docs/release build off a branch behind the latest release
#   tag still reports the true latest).  Falls back to 0.0.0-SNAPSHOT if no tag exists.

set -euo pipefail

COMPONENT=""
RELEASED=0
for arg in "$@"; do
    case "${arg}" in
        --released) RELEASED=1 ;;
        -*) echo "unknown option: ${arg}" >&2; exit 2 ;;
        *) COMPONENT="${arg}" ;;
    esac
done
: "${COMPONENT:?usage: compute-version.sh <component> [--released]}"

# On a release tag push, the version is the tag's X.Y.Z in both modes.
if [[ "${GITHUB_REF_TYPE:-}" == "tag" ]]; then
    TAG="${GITHUB_REF_NAME:-}"
    if [[ "${TAG}" =~ ^${COMPONENT}-v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
        exit 0
    fi
fi

# --released reports the global latest published version (no reachability filter); the default
# SNAPSHOT mode bumps from this branch's own history, so there it is filtered to --merged HEAD.
if [[ "${RELEASED}" -eq 1 ]]; then
    LAST_TAG="$(git tag --sort=-version:refname --list "${COMPONENT}-v*" | head -1)"
else
    LAST_TAG="$(git tag --merged HEAD --sort=-version:refname --list "${COMPONENT}-v*" | head -1)"
fi
if [[ -z "${LAST_TAG}" ]]; then
    echo "0.0.0-SNAPSHOT"
    exit 0
fi

if [[ "${LAST_TAG}" =~ ^${COMPONENT}-v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    if [[ "${RELEASED}" -eq 1 ]]; then
        # Latest released version, as-is.
        echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
    else
        # Next snapshot: bump patch.
        echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))-SNAPSHOT"
    fi
else
    echo "0.0.0-SNAPSHOT"
fi
