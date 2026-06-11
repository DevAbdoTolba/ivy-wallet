@echo off
setlocal

set "TITLE=%~1"
if "%TITLE%"=="" (
  echo Usage: capture.bat ^<title^>
  echo.
  echo Captures adb logcat output filtered to the SmsTrace tag.
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
  echo adb not found or device not connected — connect device and re-run.
  exit /b 1
)

echo.
echo File: %OUT%
echo Reproduce the bug now. Press Ctrl+C when done.
echo Live markers below ^(full text in the .log file^):
echo.

REM Pipeline: adb stdout → PowerShell ForEach. Each line is appended to the
REM .log file verbatim AND a short marker (the first whitespace-delimited
REM token after the SmsTrace: tag) is echoed to the console so the user sees
REM live progress without the noise of full bodies. The marker regex picks
REM up SCAN / ROUTE / FIND / APPLY_TEMPLATE / SEED_FROM_SCREEN / ROLE_PICK /
REM MAP_SAVE / CREATE / etc. — everything we tag from the SMS pipeline.
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { $out = '%OUT%'; adb logcat -s SmsTrace LoanTrace | ForEach-Object { Add-Content -LiteralPath $out -Value $_; if ($_ -match '(?:SmsTrace|LoanTrace):\s*(\S+)') { Write-Host ('  > ' + $matches[1]) -ForegroundColor Cyan } } }"

endlocal
