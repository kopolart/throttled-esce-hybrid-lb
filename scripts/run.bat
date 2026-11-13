@echo off
setlocal
cd /d "%~dp0.."

set SCENARIO=%1
if "%SCENARIO%"=="" set SCENARIO=1

if not exist build\classes call scripts\build.bat
if errorlevel 1 exit /b 1

java -Dlb.scenario=%SCENARIO% -cp "build/classes;jars/*" cloudsim.ext.gui.GuiMain
