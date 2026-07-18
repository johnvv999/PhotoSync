@echo off
REM push.bat — commits and pushes local changes in this folder to
REM https://github.com/johnvv999/photosync
REM
REM Usage:
REM   push.bat "your commit message"
REM If you omit the message, it falls back to a timestamped default.

setlocal

cd /d "%~dp0"

if not exist ".git" (
    echo No git repo found here yet. Run this once first:
    echo   git init
    echo   git remote add origin https://github.com/johnvv999/photosync.git
    echo   git branch -M main
    exit /b 1
)

set "MSG=%~1"
if "%MSG%"=="" (
    for /f "tokens=1-3 delims=/ " %%a in ('date /t') do set DATESTAMP=%%a-%%b-%%c
    for /f "tokens=1-2 delims=: " %%a in ('time /t') do set TIMESTAMP=%%a%%b
    set "MSG=Update %DATESTAMP% %TIMESTAMP%"
)

echo Staging changes...
git add .

git diff --cached --quiet
if %errorlevel%==0 (
    echo Nothing to commit — working tree matches last commit.
    exit /b 0
)

echo Committing: %MSG%
git commit -m "%MSG%"

echo Pushing to origin main...
git push origin main

if %errorlevel% neq 0 (
    echo.
    echo Push failed. If this is the first push, try:
    echo   git push -u origin main
    exit /b 1
)

echo Done.
endlocal