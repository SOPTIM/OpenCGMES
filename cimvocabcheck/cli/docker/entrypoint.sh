#!/bin/sh
# `cimvocabcheck` wrapper installed on the image PATH. Forwards all arguments to the bundled CLI
# fat JAR. Set JAVA_OPTS to tune the JVM (e.g. heap size) when running the container.
#
# commons-io still frees buffers through sun.misc.Unsafe, which JDK 24+ warns about on stderr on
# every run. The image pins its JRE, so the flag that allows it is always recognised here; a JAR
# run on an older JDK cannot take it. JAVA_OPTS comes after it and can override it.
exec java --sun-misc-unsafe-memory-access=allow ${JAVA_OPTS:-} -jar /opt/cimvocabcheck/cli.jar "$@"
