@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_USER_HOME=%CD%\.gradle"
title Nsight Launcher - MinimalDemo

echo Starting MinimalDemo...
echo In Nsight Graphics, attach to the java.exe process for MinimalDemo.
call gradlew.bat runMinimalDemo --no-daemon --console=plain
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
