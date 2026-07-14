@echo off
setlocal
cd /d "%~dp0.."
set "GRADLE_USER_HOME=%CD%\.gradle"
title Nsight Launcher - StressDemo

set "SHAPE=triangle"
set "INSTANCES=100000"
set "MODE=gpu"
if not "%~1"=="" set "SHAPE=%~1"
if not "%~2"=="" set "INSTANCES=%~2"
if not "%~3"=="" set "MODE=%~3"

echo Starting StressDemo %MODE% mode with %INSTANCES% %SHAPE% instances...
echo Usage: 04_stress_demo.bat [triangle^|quad^|cube] [instance-count] [gpu^|indexed^|indexed-ssbo^|dynamic]
echo In Nsight Graphics, attach to the java.exe process whose window title starts with Stress.
call gradlew.bat runStressDemo -PstressShape=%SHAPE% -PstressInstances=%INSTANCES% -PstressMode=%MODE% --no-daemon --console=plain
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
