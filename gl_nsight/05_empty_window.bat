@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_USER_HOME=%CD%\.gradle"
title Nsight Launcher - EmptyWindowDemo

echo Starting an OpenGL window with no clear or draw commands...
echo In Nsight Graphics, attach to the java.exe process whose window title starts with Haikalat Empty Window.
call gradlew.bat runEmptyWindowDemo --no-daemon --console=plain
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
