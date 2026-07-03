#!/usr/bin/env bash
# Check or apply the SOPTIM Apache-2.0 license header across all first-party sources.
#
# Usage: scripts/license-headers.sh [check|format]      (default: check)
#
#   check   -> fail if any first-party source file is missing / has a wrong header
#   format  -> insert or rewrite the header in place (auto-fix)
#
# One header, four gates (kept byte-for-byte identical):
#
#   Maven    root + cimvocabcheck: mycila license-maven-plugin (pom.xml) and
#            Spotless google-java-format (src/**/*.java).
#   IntelliJ cimnotebook/intellij: Spotless (Kotlin, *.gradle.kts, plugin.xml, README).
#   VS Code  cimnotebook/vscode:   ESLint `soptim/copyright-header` rule (TS/JS/MJS).
#
# The header text lives in license-header*.txt / license-header-style.xml (Maven),
# cimnotebook/intellij/config/license/ (IntelliJ) and the ESLint rule
# (cimnotebook/vscode/eslint-rules/). The IntelliJ IDE inserts the same header from
# .idea/copyright/.
#
# NOTE: cimxml is intentionally NOT covered yet — its header migration is staged
# (skipped by default). See README / the cimxml activation notes.
#
# Requires: mvn; the IntelliJ Gradle wrapper; node/npm (with `npm ci` already run
# in cimnotebook/vscode). Components whose toolchain is unavailable are skipped
# with a warning.
set -euo pipefail

MODE="${1:-check}"
case "$MODE" in
    check | format) ;;
    *)
        echo "usage: $0 [check|format]" >&2
        exit 2
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MYCILA="com.mycila:license-maven-plugin:5.0.0"
CIMVOCABCHECK_JAVA_MODULES="cimvocabcheck/core,cimvocabcheck/cli,cimvocabcheck/lsp"
rc=0

echo "==> Maven (root + cimvocabcheck)"
if command -v mvn >/dev/null 2>&1; then
    if [ "$MODE" = "check" ]; then
        mvn -B -ntp "${MYCILA}:check" || rc=1
        mvn -B -ntp -pl "$CIMVOCABCHECK_JAVA_MODULES" spotless:check || rc=1
    else
        mvn -B -ntp "${MYCILA}:format"
        mvn -B -ntp -pl "$CIMVOCABCHECK_JAVA_MODULES" spotless:apply
    fi
else
    echo "   ! mvn not found — skipping Maven headers" >&2
fi

echo "==> IntelliJ plugin (cimnotebook/intellij)"
if [ -x cimnotebook/intellij/gradlew ]; then
    gradle_task=$([ "$MODE" = "check" ] && echo spotlessCheck || echo spotlessApply)
    (cd cimnotebook/intellij && ./gradlew --console=plain "$gradle_task") || rc=1
else
    echo "   ! cimnotebook/intellij/gradlew not found — skipping IntelliJ headers" >&2
fi

echo "==> VS Code extension (cimnotebook/vscode)"
if [ -d cimnotebook/vscode/node_modules ]; then
    npm_script=$([ "$MODE" = "check" ] && echo lint || echo lint:fix)
    (cd cimnotebook/vscode && npm run --silent "$npm_script") || rc=1
else
    echo "   ! cimnotebook/vscode/node_modules missing (run 'npm ci') — skipping VS Code headers" >&2
fi

if [ "$MODE" = "check" ] && [ "$rc" -ne 0 ]; then
    echo "License header check FAILED. Run 'scripts/license-headers.sh format' to fix." >&2
fi
exit "$rc"
