#!/bin/sh
# `cimxml2jsonld` wrapper installed on the image PATH. Forwards all arguments to the bundled fat JAR.
# The heap defaults to 75% of the container memory; set CIMXML2JSONLD_OPTS (or JAVA_OPTS) to
# override it, e.g. -e CIMXML2JSONLD_OPTS=-Xmx8g.
# shellcheck disable=SC2086
exec java -XX:MaxRAMPercentage=75 ${JAVA_OPTS:-} ${CIMXML2JSONLD_OPTS:-} -jar /opt/cimxml-cli/cli.jar "$@"
