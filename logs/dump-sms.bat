@echo off
setlocal

if "%~1"=="" (
  echo Usage: dump-sms.bat ^<sender1^> [sender2] [sender3] ...
  echo.
  echo Pulls SMS bodies from each named sender into a timestamped file
  echo in this folder. The sender id must match the SMS address exactly,
  echo e.g. VF-Cash, Vodafone, MyBank. Adds one section per sender.
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dump-sms.ps1" %*

endlocal
