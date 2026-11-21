@echo off
rem ============================================================================
rem  R-6: build wrapper for NATIVE Windows cmd / PowerShell.
rem
rem  Why this file exists: the default shell's JAVA_HOME points at JDK 17, so a
rem  bare "mvn" is rejected by maven-enforcer-plugin during the validate phase.
rem  build.sh already handles that, but it needs bash (Git Bash / MSYS / WSL).
rem  This script gives native cmd / PowerShell the same four-probe behaviour.
rem
rem  Usage (all arguments are forwarded to Maven verbatim; no argument means
rem  "clean package"):
rem    .\tools\build.cmd                          = mvn clean package
rem    .\tools\build.cmd -o compile               = mvn -o compile
rem    .\tools\build.cmd -o test-compile
rem    .\tools\build.cmd -DskipTests clean package
rem
rem  The script lives in <repo root>\tools\ and switches the working directory to
rem  the repo root on start, so it can be invoked from anywhere.
rem
rem  Overridable environment variables (same names as build.sh):
rem    HV_JDK21_HOME   JDK 21 root. If it does not point at a usable JDK 21 the
rem                    script FAILS LOUDLY (exit 1). No silent fallback.
rem    HV_JDK21_GLOB   JDK 21 discovery glob (default D:\Java\zulu21.48.17-*)
rem    HV_MAVEN        Maven executable (default D:\maven\apache-maven-3.9.9\bin\mvn.cmd)
rem    MAVEN_OPTS      Never overwritten; only the missing UTF-8 options are appended.
rem
rem  Exit code: Maven's real ERRORLEVEL is propagated, so a failing build can
rem  never be reported as green by CI.
rem
rem  NOTE ON ENCODING: this file is intentionally 100%% ASCII (CRLF line endings).
rem  cmd.exe mis-seeks inside a batch file that mixes UTF-8 multibyte text with
rem  long lines, which makes "rem" lines execute as commands and can silently
rem  corrupt control flow. ASCII keeps the parser deterministic on every locale.
rem ============================================================================

setlocal EnableExtensions EnableDelayedExpansion

rem ---- Locate the repo root ------------------------------------------------
rem This script lives in <repo root>\tools\; Maven must run where pom.xml is.
rem Derive the root from the script's own location instead of the caller's cwd,
rem so invoking it from any directory works. pushd also covers UNC-safe restore.
for %%I in ("%~dp0..") do set "HV_REPO_ROOT=%%~fI"
if not exist "%HV_REPO_ROOT%\pom.xml" (
    echo [build.cmd][ERROR] pom.xml not found in "%HV_REPO_ROOT%"
    echo [build.cmd][ERROR] Keep this script at "repo-root\tools\build.cmd"
    endlocal & exit /b 1
)
pushd "%HV_REPO_ROOT%" 2>nul
if errorlevel 1 (
    echo [build.cmd][ERROR] Cannot switch to repo root: "%HV_REPO_ROOT%"
    endlocal & exit /b 1
)

rem Let Maven emit its (localized) output as UTF-8 instead of the OEM codepage.
rem The caller's codepage is captured first and restored before exiting, because
rem "chcp" changes console state that setlocal/endlocal does NOT roll back.
set "_HV_OLD_CP="
for /f "tokens=2 delims=:" %%c in ('chcp') do set "_HV_OLD_CP=%%c"
set "_HV_OLD_CP=!_HV_OLD_CP: =!"
if defined _HV_OLD_CP chcp 65001 >nul 2>&1

set "HV_TAG=[build.cmd]"

rem ---- MAVEN_OPTS: append only the options that are not already there --------
call :add_opt "-Dfile.encoding=UTF-8"
call :add_opt "-Dstdout.encoding=UTF-8"
call :add_opt "-Dstderr.encoding=UTF-8"

set "MVN=%HV_MAVEN%"
if not defined MVN set "MVN=D:\maven\apache-maven-3.9.9\bin\mvn.cmd"

set "JDK21_GLOB=%HV_JDK21_GLOB%"
if not defined JDK21_GLOB set "JDK21_GLOB=D:\Java\zulu21.48.17-*"

set "JDK21_HOME="
set "HV_SRC="

rem ============================================================================
rem  Probe 1: JAVA_HOME is already a JDK 21 -> use it (explicit intent wins)
rem ============================================================================
if not defined JAVA_HOME goto :try_override
call :strip_trailing_slash JAVA_HOME
call :is_jdk21 "%JAVA_HOME%"
if errorlevel 1 goto :try_override
set "JDK21_HOME=%JAVA_HOME%"
set "HV_SRC=JAVA_HOME"
echo %HV_TAG% JAVA_HOME is already JDK 21: %JDK21_HOME%
goto :found

rem ============================================================================
rem  Probe 2: HV_JDK21_HOME override -> must be a usable JDK 21, else exit 1
rem ============================================================================
:try_override
if not defined HV_JDK21_HOME goto :try_glob
call :strip_trailing_slash HV_JDK21_HOME
call :is_jdk21 "%HV_JDK21_HOME%"
if errorlevel 1 goto :err_override
set "JDK21_HOME=%HV_JDK21_HOME%"
set "HV_SRC=HV_JDK21_HOME"
echo %HV_TAG% using HV_JDK21_HOME: %JDK21_HOME%
goto :found

:err_override
echo %HV_TAG%[ERROR] HV_JDK21_HOME does not point at a usable JDK 21: %HV_JDK21_HOME%
if not exist "%HV_JDK21_HOME%" (
    echo %HV_TAG%[ERROR] That directory does not exist. Check the path spelling.
) else (
    echo %HV_TAG%[ERROR] Directory exists but is missing release / bin\java.exe, or its major version is not 21.
    call :report_major "%HV_JDK21_HOME%"
)
echo %HV_TAG%[ERROR] Fix HV_JDK21_HOME, or clear it so the script falls back to glob discovery.
echo %HV_TAG%[ERROR] Refusing to continue: probing would silently ignore your explicit override.
goto :fail

rem ============================================================================
rem  Probe 3: HV_JDK21_GLOB discovery (name-sorted descending, newest first)
rem ============================================================================
:try_glob
set "HV_GLOB_HIT="
call :dirname "%JDK21_GLOB%"
set "_HV_DIR_BASE=%_HV_DIR%"
for /f "usebackq delims=" %%n in (`dir /b /ad /o-n "%JDK21_GLOB%" 2^>nul`) do (
    if not defined HV_GLOB_HIT (
        if defined _HV_DIR_BASE (
            call :is_jdk21 "!_HV_DIR_BASE!\%%n"
            if not errorlevel 1 set "HV_GLOB_HIT=!_HV_DIR_BASE!\%%n"
        ) else (
            call :is_jdk21 "%%n"
            if not errorlevel 1 set "HV_GLOB_HIT=%%n"
        )
    )
)
if not defined HV_GLOB_HIT goto :err_not_found
set "JDK21_HOME=%HV_GLOB_HIT%"
set "HV_SRC=HV_JDK21_GLOB"
echo %HV_TAG% discovered JDK 21: %JDK21_HOME%
goto :found

rem ============================================================================
rem  Probe 4: nothing usable -> report probed path, current JAVA_HOME, remedies
rem ============================================================================
:err_not_found
echo %HV_TAG%[ERROR] No usable JDK 21 found; cannot build HilltopVillage.
echo %HV_TAG%[ERROR] Probed glob: %JDK21_GLOB%
if not defined JAVA_HOME (
    echo %HV_TAG%[ERROR] JAVA_HOME is not set.
) else (
    echo %HV_TAG%[ERROR] JAVA_HOME=%JAVA_HOME%
    call :report_major "%JAVA_HOME%"
)
echo %HV_TAG%[ERROR] Fix it with any one of:
echo %HV_TAG%[ERROR]   1) set HV_JDK21_HOME=D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64
echo %HV_TAG%[ERROR]   2) set JAVA_HOME=D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64
echo %HV_TAG%[ERROR]   3) set HV_JDK21_GLOB=D:\wherever\jdk21-*
echo %HV_TAG%[ERROR] Reference path on this machine: D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64
goto :fail

rem ============================================================================
rem  JDK 21 found: validate Maven, export JAVA_HOME, prepend JDK bin to PATH
rem ============================================================================
:found
call :resolve_mvn
if errorlevel 1 (
    echo %HV_TAG%[ERROR] Maven not found: %MVN%
    echo %HV_TAG%[ERROR] Override it with: set HV_MAVEN=D:\maven\apache-maven-3.9.9\bin\mvn.cmd
    goto :fail
)

set "JAVA_HOME=%JDK21_HOME%"
set "PATH=%JDK21_HOME%\bin;%PATH%"

echo %HV_TAG% JDK source: %HV_SRC%
echo %HV_TAG% JAVA_HOME=%JAVA_HOME%

rem ---- forward arguments verbatim; no argument means "clean package" --------
if "%~1"=="" goto :run_default
echo %HV_TAG% running: mvn %*
call "%MVN%" %*
set "HV_RC=!ERRORLEVEL!"
goto :finish

:run_default
echo %HV_TAG% running: mvn clean package
call "%MVN%" clean package
set "HV_RC=!ERRORLEVEL!"
goto :finish

:finish
echo %HV_TAG% Maven exit code=%HV_RC%
if defined _HV_OLD_CP chcp !_HV_OLD_CP! >nul 2>&1
popd
endlocal & exit /b %HV_RC%

:fail
echo %HV_TAG% aborted before invoking Maven: environment is not usable.
if defined _HV_OLD_CP chcp !_HV_OLD_CP! >nul 2>&1
popd
endlocal & exit /b 1

rem ============================================================================
rem  Subroutines (all locals use the _HV_ prefix to avoid clobbering callers)
rem ============================================================================

rem add_opt <option> -- append to MAVEN_OPTS unless already present
:add_opt
set "_HV_O=%~1"
if not defined MAVEN_OPTS (
    set "MAVEN_OPTS=%_HV_O%"
    exit /b 0
)
set "_HV_T=!MAVEN_OPTS:%_HV_O%=__HV_HIT__!"
if not "!_HV_T!"=="!MAVEN_OPTS!" exit /b 0
set "MAVEN_OPTS=!MAVEN_OPTS! %_HV_O%"
exit /b 0

rem is_jdk21 <dir> -- errorlevel 0 when <dir> is a JDK root whose major is 21
rem  Reads JAVA_VERSION from the release file instead of parsing "java -version":
rem  that output goes to stderr, and 2>&1 redirection differs between cmd and
rem  PowerShell, which made for /f based parsing unreliable.
:is_jdk21
set "_HV_JH=%~1"
if not defined _HV_JH exit /b 1
if not exist "%_HV_JH%\release" exit /b 1
if not exist "%_HV_JH%\bin\java.exe" if not exist "%_HV_JH%\bin\java" exit /b 1
set "_HV_JV="
for /f "usebackq tokens=2 delims==" %%v in (`findstr /b /c:"JAVA_VERSION=" "%_HV_JH%\release"`) do if not defined _HV_JV set "_HV_JV=%%v"
if not defined _HV_JV exit /b 1
set "_HV_JV=!_HV_JV:"=!"
for /f "tokens=1 delims=." %%m in ("!_HV_JV!") do set "_HV_MAJ=%%m"
if "!_HV_MAJ!"=="1" for /f "tokens=2 delims=." %%m in ("!_HV_JV!") do set "_HV_MAJ=%%m"
if "!_HV_MAJ!"=="21" exit /b 0
exit /b 1

rem report_major <dir> -- print JAVA_VERSION of <dir> for diagnostics
:report_major
set "_HV_JH=%~1"
set "_HV_JV="
if exist "%_HV_JH%\release" for /f "usebackq tokens=2 delims==" %%v in (`findstr /b /c:"JAVA_VERSION=" "%_HV_JH%\release"`) do if not defined _HV_JV set "_HV_JV=%%v"
if not defined _HV_JV (
    echo %HV_TAG%[ERROR] detected version: unknown (no release file; this may not be a JDK at all)
) else (
    set "_HV_JV=!_HV_JV:"=!"
    echo %HV_TAG%[ERROR] detected version: !_HV_JV!
)
exit /b 0

rem resolve_mvn -- errorlevel 0 when MVN resolves (existing file, or a bare
rem  command name found on PATH); a path that does not exist is a hard failure
:resolve_mvn
if exist "%MVN%" exit /b 0
call :has_backslash "%MVN%"
if not errorlevel 1 exit /b 1
where "%MVN%" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

rem has_backslash <str> -- errorlevel 0 when the string contains a backslash
:has_backslash
set "_HV_S=%~1"
if "!_HV_S:\=!"=="!_HV_S!" exit /b 1
exit /b 0

rem dirname <path> -- everything before the last backslash, stored in _HV_DIR
:dirname
set "_HV_G=%~1"
set "_HV_L=-1"
set /a _HV_I=0
:dirname_loop
set "_HV_C=!_HV_G:~%_HV_I%,1!"
if "!_HV_C!"=="" goto :dirname_done
if "!_HV_C!"=="\" set "_HV_L=!_HV_I!"
set /a _HV_I+=1
goto :dirname_loop
:dirname_done
set "_HV_DIR="
if not "!_HV_L!"=="-1" for %%n in (!_HV_L!) do set "_HV_DIR=!_HV_G:~0,%%n!"
exit /b 0

rem strip_trailing_slash <varName> -- D:\Java\jdk21\ becomes D:\Java\jdk21
:strip_trailing_slash
set "_HV_V=!%~1!"
if "!_HV_V:~-1!"=="\" set "_HV_V=!_HV_V:~0,-1!"
set "%~1=!_HV_V!"
exit /b 0
