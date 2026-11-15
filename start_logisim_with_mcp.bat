@echo off
setlocal
if not defined LOGISIM_EXE (
    if exist "%~dp0logisim-win-2.7.1.exe" (
        set "LOGISIM_EXE=%~dp0logisim-win-2.7.1.exe"
    ) else if exist "%~dp0logisim-generic-2.7.1.jar" (
        set "LOGISIM_EXE=%~dp0logisim-generic-2.7.1.jar"
    ) else if exist "%USERPROFILE%\Desktop\All\logisim-win-2.7.1.exe" (
        set "LOGISIM_EXE=%USERPROFILE%\Desktop\All\logisim-win-2.7.1.exe"
    )
)
set "BRIDGE_JAR=%~dp0logisim-mcp-bridge.jar"
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA_EXE if exist "C:\Program Files\Java\jre-1.8\bin\javaw.exe" set "JAVA_EXE=C:\Program Files\Java\jre-1.8\bin\javaw.exe"
if not defined JAVA_EXE set "JAVA_EXE=javaw"

if not exist "%LOGISIM_EXE%" (
    echo [ERROR] Logisim executable not found. Set LOGISIM_EXE or place logisim-win-2.7.1.exe alongside this script.
    exit /b 1
)

start "Logisim" "%JAVA_EXE%" -cp "%LOGISIM_EXE%;%BRIDGE_JAR%" logisimmcp.Launcher %*
