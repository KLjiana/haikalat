@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_USER_HOME=%CD%\.gradle"
title Nsight Launcher - AsyncDemo

echo Starting AsyncDemo...
echo In Nsight Graphics, attach to the java.exe process for AsyncDemo.
call gradlew.bat runAsyncDemo --no-daemon --console=plain
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
