@echo off
setlocal

if "%~1"=="" (
  echo Usage: corpus-build.bat ^<dump-file^>
  echo.
  echo Reads an SMS dump file produced by dump-sms.bat and writes a
  echo clustered, deduped JSON corpus to:
  echo   feature\sms-sync\src\test\resources\sms-corpus.json
  echo.
  echo The corpus is gitignored - it contains real bodies. Use it as the
  echo input for SmsCorpusTest, which loads it from the classpath at
  echo /sms-corpus.json.
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0corpus-build.ps1" -DumpFile "%~1"

endlocal
