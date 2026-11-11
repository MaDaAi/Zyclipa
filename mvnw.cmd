@REM Zyclipra Maven launcher: uses (and if needed downloads) Maven 3.9.9 inside this project's
@REM .tools folder, so nothing has to be installed system-wide. Dependencies go to .m2\ here too.
@echo off
setlocal
set "BASEDIR=%~dp0"
set "MVN_HOME=%BASEDIR%.tools\apache-maven-3.9.9"
if not exist "%MVN_HOME%\bin\mvn.cmd" (
  echo Downloading Maven 3.9.9 into .tools ...
  if not exist "%BASEDIR%.tools" mkdir "%BASEDIR%.tools"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile '%BASEDIR%.tools\maven.zip'; Expand-Archive -Force '%BASEDIR%.tools\maven.zip' '%BASEDIR%.tools'" || exit /b 1
)
cd /d "%BASEDIR%"
call "%MVN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
