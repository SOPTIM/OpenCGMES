#!/bin/sh
# `cimvocabcheck` wrapper installed on the image PATH. Forwards all arguments to the bundled CLI
# fat JAR. Set JAVA_OPTS to tune the JVM (e.g. heap size) when running the container.
exec java ${JAVA_OPTS:-} -jar /opt/cimvocabcheck/cli.jar "$@"
