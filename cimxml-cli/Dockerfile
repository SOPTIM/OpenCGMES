# syntax=docker/dockerfile:1
#
# cimxml2jsonld image. Bundles a JRE + the CLI fat JAR; mount the RDFS and CIMXML folders, e.g.:
#
#   docker run --rm --user "$(id -u):$(id -g)" -v "${PWD}:/work" ghcr.io/soptim/cimxml-cli:latest \
#     -s rdfs -m igm -o igm.jsonld.gz
#
# Build from the repository root (the build needs cimxml/ + cimxml-cli/):
#
#   docker build -f cimxml-cli/Dockerfile -t cimxml-cli .

# ---- Stage 1: build the CLI fat JAR from source ----------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml ./
COPY cimxml/ ./cimxml/
COPY cimxml-cli/ ./cimxml-cli/

# Tests and linters are skipped here; they are gated by the normal CI `verify`
RUN --mount=type=cache,target=/root/.m2 \
    SKIP="-DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dpmd.skip=true -Djacoco.skip=true -Dlicense.skip=true -Dassembly.skipAssembly=true" && \
    mvn -B -ntp -f cimxml/pom.xml $SKIP clean install && \
    mvn -B -ntp -f cimxml-cli/pom.xml $SKIP clean package && \
    cp cimxml-cli/target/cimxml-cli-*.jar /cli.jar

# ---- Stage 2: slim runtime -------------------------------------------------------------------
FROM eclipse-temurin:21.0.11_10-jre AS runtime

LABEL org.opencontainers.image.title="cimxml2jsonld" \
      org.opencontainers.image.description="Convert the CIMXML files of a CGMES IGM into a queryable JSON-LD dataset." \
      org.opencontainers.image.source="https://github.com/SOPTIM/OpenCGMES" \
      org.opencontainers.image.url="https://opencgmes.soptim.de/cimxml-cli/overview" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="SOPTIM AG"

COPY --from=build /cli.jar /opt/cimxml-cli/cli.jar
COPY cimxml-cli/docker/entrypoint.sh /usr/local/bin/cimxml2jsonld
RUN chmod 0755 /usr/local/bin/cimxml2jsonld

WORKDIR /work
ENTRYPOINT ["cimxml2jsonld"]
CMD ["--help"]
