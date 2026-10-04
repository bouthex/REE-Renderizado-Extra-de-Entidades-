@echo off
cd /d "%~dp0"
call gradlew.bat build
echo.
echo Si dice BUILD SUCCESSFUL, el mod esta en build\libs\ree-0.2.0.jar
pause
