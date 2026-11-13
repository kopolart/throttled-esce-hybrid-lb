@echo off
setlocal
cd /d "%~dp0.."

if not exist build\classes call scripts\build.bat
if errorlevel 1 exit /b 1

if exist build\test-classes rmdir /s /q build\test-classes
mkdir build\test-classes

javac -encoding UTF-8 -source 1.8 -target 1.8 -cp "build/classes;jars/*" -d build\test-classes test\cloudsim\ext\datacenter\LoadBalancerConfigTest.java
if errorlevel 1 exit /b 1

java -cp "build\classes;build\test-classes" cloudsim.ext.datacenter.LoadBalancerConfigTest
