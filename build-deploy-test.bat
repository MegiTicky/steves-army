@echo off
setlocal EnableExtensions

set "PROJECT_DIR=%~dp0"
set "TEST_INSTANCE=C:\Users\lauya\curseforge\minecraft\Instances\Test"
set "TEST_MODS=%TEST_INSTANCE%\mods"
set "JAVA_HOME=C:\Program Files\Java\jdk-17"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java 17 was not found at "%JAVA_HOME%".
    exit /b 1
)

if not exist "%TEST_MODS%" (
    echo ERROR: Test instance mods directory was not found at "%TEST_MODS%".
    exit /b 1
)

pushd "%PROJECT_DIR%"
call :select_artifact
if not defined ARTIFACT (
    echo No existing deployable JAR was found in "%PROJECT_DIR%build\libs".
    echo A new build is required.
) else (
    echo Current selected deploy JAR: "%ARTIFACT%"
)

choice /c YN /n /m "Build a new JAR before deploying? [Y/N] "
if errorlevel 2 goto :after_build

echo.
echo Building mod JAR...
call gradlew.bat build
if errorlevel 1 (
    echo ERROR: Gradle build failed. The existing Test instance JAR was not changed.
    popd
    exit /b 1
)

:after_build
call :select_artifact
if not defined ARTIFACT (
    echo ERROR: No JAR was produced in "%PROJECT_DIR%build\libs".
    popd
    exit /b 1
)
echo Selected deploy JAR: "%ARTIFACT%"

set "SOURCE=%PROJECT_DIR%build\libs\%ARTIFACT%"
set "DESTINATION=%TEST_MODS%\%ARTIFACT%"

copy /y "%SOURCE%" "%DESTINATION%" >nul
if errorlevel 1 (
    echo ERROR: Failed to deploy "%ARTIFACT%" to "%TEST_MODS%".
    popd
    exit /b 1
)

echo Deployed "%ARTIFACT%" to:
echo %TEST_MODS%
for %%F in ("%DESTINATION%") do echo Size: %%~zF bytes

popd
exit /b 0

:select_artifact
set "ARTIFACT="
for /f "delims=" %%F in ('dir /b /a-d /o-d "build\libs\*.jar" 2^>nul') do if not defined ARTIFACT set "ARTIFACT=%%F"
exit /b
