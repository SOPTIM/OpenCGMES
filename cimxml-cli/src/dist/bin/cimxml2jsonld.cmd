@rem    Copyright (c) 2026 SOPTIM AG
@rem    SPDX-License-Identifier: Apache-2.0
@rem
@rem Starts cimxml2jsonld. The JVM gets a default maximum heap of @default.heap@; to change it or to
@rem pass other JVM options, set CIMXML2JSONLD_OPTS (or JAVA_OPTS), e.g. set CIMXML2JSONLD_OPTS=-Xmx8g
@rem Uses %JAVA_HOME%\bin\java.exe if JAVA_HOME is set, otherwise java from the PATH.
@echo off
setlocal
set "HOME_DIR=%~dp0.."
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)
@rem Later options win: the defaults come first, user options last
"%JAVA_EXE%" -Xmx@default.heap@ %JAVA_OPTS% %CIMXML2JSONLD_OPTS% -jar "%HOME_DIR%\lib\cimxml-cli-${project.version}.jar" %*
exit /b %ERRORLEVEL%
