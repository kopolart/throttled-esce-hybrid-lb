@echo off
setlocal
cd /d "%~dp0.."

if exist build\classes rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\*.java > build\sources.txt

javac -encoding UTF-8 -source 1.8 -target 1.8 -cp "jars/*" -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1

xcopy /E /I /Y src\resources\* build\classes\ >nul
echo Build complete: build\classes
