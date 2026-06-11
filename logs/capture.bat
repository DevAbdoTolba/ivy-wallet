@echo off
setlocal

REM UTF-8 console: adb emits UTF-8 bytes (Arabic SMS bodies, RTL marks, trace
REM arrows); under the default OEM code page every multibyte char is mangled
REM irreversibly. Keep this file pure ASCII so cmd parses it under any page.
chcp 65001 >nul

set "TITLE=%~1"
if "%TITLE%"=="" (
  echo Usage: capture.bat ^<title^>
  echo.
  echo Captures adb logcat filtered to the SmsTrace, LoanTrace, AndroidRuntime
  echo and ActivityManager tags, so crashes and ANRs land in the capture too.
  echo Full lines go to a date-prefixed .log file in this folder.
  echo Terminal echoes a short marker per line so you can see actions live.
  exit /b 1
)

REM PowerShell-derived ISO timestamp keeps filenames sortable on every Windows
REM build (wmic is deprecated on Windows 11). Format: 2026-05-09_14-32-09.
for /f %%a in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd_HH-mm-ss"') do set "STAMP=%%a"
set "OUT=%~dp0%STAMP%_%TITLE%.log"

echo.
echo Clearing logcat buffer ...
adb logcat -c
if errorlevel 1 (
  echo adb not found or device not connected - connect device and re-run.
  exit /b 1
)

echo.
echo File: %OUT%
echo Reproduce the bug now. Press Ctrl+C when done.
echo Live markers below ^(full text in the .log file^):
echo.

REM Pipeline: adb stdout -> PowerShell ForEach. logcat has no encoding flag
REM ('-v UTF-8' does not exist; -v takes only format verbs like threadtime
REM plus modifiers like printable/usec), so UTF-8 safety is host-side:
REM [Console]::OutputEncoding makes PS 5.1 decode adb's stdout as UTF-8 and
REM Add-Content -Encoding utf8 re-encodes it losslessly. Each line is appended
REM to the .log file verbatim AND a short marker (the first whitespace-
REM delimited token after the tag) is echoed to the console so the user sees
REM live progress without the noise of full bodies. The marker regex picks up
REM SCAN / ROUTE / FIND / APPLY_TEMPLATE / SEED_FROM_SCREEN / ROLE_PICK /
REM MAP_SAVE / CREATE / etc. plus AndroidRuntime's FATAL the moment a crash
REM hits; ActivityManager is captured to the file but not echoed - it is too
REM chatty for live markers.
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $out = '%OUT%'; adb logcat -s SmsTrace LoanTrace AndroidRuntime ActivityManager | ForEach-Object { Add-Content -LiteralPath $out -Value $_ -Encoding utf8; if ($_ -match '(?:SmsTrace|LoanTrace|AndroidRuntime):\s*(\S+)') { Write-Host ('  > ' + $matches[1]) -ForegroundColor Cyan } } }"

endlocal
