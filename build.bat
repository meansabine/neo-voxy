@echo off
echo =========================================
echo Building NeoVoxy Client and Server Jars
echo =========================================
echo Cleaning project...
call gradlew.bat clean
if %ERRORLEVEL% neq 0 goto error

echo Building Unified/Client Jar...
call gradlew.bat build
if %ERRORLEVEL% neq 0 goto error
