@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_USER_HOME=%CD%\.gradle"
title Nsight Launcher - UiDemo

echo Starting retained-mode UiDemo...
echo In Nsight Graphics, attach to the java.exe process whose window title starts with Haikalat UiDemo.
call gradlew.bat runUiDemo --no-daemon --console=plain
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
