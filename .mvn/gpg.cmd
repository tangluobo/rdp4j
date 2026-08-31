@echo off
setlocal EnableExtensions EnableDelayedExpansion

for /f "delims=" %%G in ('where gpg.exe 2^>nul') do (
    "%%~fG" %*
    exit /b !errorlevel!
)

for /f "delims=" %%G in ('where git.exe 2^>nul') do (
    for %%R in ("%%~dpG..") do set "GIT_ROOT=%%~fR"
    if exist "!GIT_ROOT!\usr\bin\gpg.exe" (
        "!GIT_ROOT!\usr\bin\gpg.exe" %*
        exit /b !errorlevel!
    )
)

echo Unable to find gpg.exe. Install GnuPG or Git for Windows. 1>&2
exit /b 1
