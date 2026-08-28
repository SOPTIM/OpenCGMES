# syntax=docker/dockerfile:1
#
# Public, schema-less CimVocabCheck CLI image. Bundles a JRE + the CLI fat JAR; bring your own
# schema and inputs by mounting them, e.g.:
#
#   docker run --rm -v "$PWD:/work" ghcr.io/soptim/cimvocabcheck-cli:latest \
#     --schema schemas -f codequality shapes/*.ttl
#
# Build from the repository root (the build needs cimxml/ + cimvocabcheck/):
#
#   docker build -f cimvocabcheck/Dockerfile -t cimvocabcheck-cli .

# ---- Stage 1: build the CLI fat JAR from source ----------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Only the modules needed to build the CLI; .dockerignore prunes target/, the ENTSO-E test
# submodule, docs, etc. Module poms are standalone (no <parent>), so each -f build resolves the
# previous one from the local repo.
COPY pom.xml ./
COPY cimxml/ ./cimxml/
COPY cimvocabcheck/ ./cimvocabcheck/

# Build cimxml -> core (installed into the local repo), then package the cli fat JAR. Tests and
# linters are skipped here — the published image only needs the artifact; correctness (formatting,
# SpotBugs, PMD, coverage, tests) is gated by the normal CI `verify`. The cache mount keeps Maven
# dependencies across image builds.
RUN --mount=type=cache,target=/root/.m2 \
    SKIP="-DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dpmd.skip=true -Djacoco.skip=true -Dlicense.skip=true" && \
    mvn -B -ntp -f cimxml/pom.xml $SKIP clean install && \
    mvn -B -ntp -f cimvocabcheck/core/pom.xml $SKIP clean install && \
    mvn -B -ntp -f cimvocabcheck/cli/pom.xml $SKIP clean package && \
    cp cimvocabcheck/cli/target/cimvocabcheck-cli-*.jar /cli.jar

# ---- Stage 2: slim runtime -------------------------------------------------------------------
FROM eclipse-temurin:25.0.4_7-jre AS runtime

LABEL org.opencontainers.image.title="CIMVocabCheck CLI" \
      org.opencontainers.image.description="Validate SPARQL queries and SHACL shapes against CIM/CGMES schema profiles." \
      org.opencontainers.image.source="https://github.com/SOPTIM/OpenCGMES" \
      org.opencontainers.image.url="https://opencgmes.soptim.de/cimvocabcheck/cli" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="SOPTIM AG"

COPY --from=build /cli.jar /opt/cimvocabcheck/cli.jar
COPY cimvocabcheck/cli/docker/entrypoint.sh /usr/local/bin/cimvocabcheck
RUN chmod 0755 /usr/local/bin/cimvocabcheck

# Inputs and schema are mounted here; pass repo-relative paths so a Code Quality report's
# location.path lines up with the source tree.
WORKDIR /work
ENTRYPOINT ["cimvocabcheck"]
CMD ["--help"]
