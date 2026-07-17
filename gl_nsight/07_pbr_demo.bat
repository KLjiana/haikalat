@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_USER_HOME=%CD%\.gradle"
title Nsight Launcher - PbrDemo

echo Starting retained-mode PbrDemo...
echo In Nsight Graphics, attach to the java.exe process whose window title starts with Haikalat PbrDemo.
call gradlew.bat runPbrDemo --no-daemon --console=plain
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
