@echo off
setlocal

echo VitalSignal Ollama over Tailscale - connectivity setup
echo This sends no prompts or health data.
echo.

where ollama >nul 2>nul
if errorlevel 1 (
  echo ERROR: Ollama is not installed or is not on PATH.
  goto :failed
)

where tailscale >nul 2>nul
if errorlevel 1 (
  echo ERROR: Tailscale is not installed or is not on PATH.
  goto :failed
)

echo Checking Ollama...
ollama --version
if errorlevel 1 goto :failed

curl.exe --silent --show-error --fail --max-time 5 http://127.0.0.1:11434/api/version
if errorlevel 1 (
  echo.
  echo ERROR: Ollama is not responding on localhost port 11434.
  echo Start Ollama, then run this file again.
  goto :failed
)

echo.
echo Disabling Ollama cloud features for future Ollama processes...
setx OLLAMA_NO_CLOUD 1 >nul
if errorlevel 1 goto :failed

echo Configuring tailnet-only HTTPS proxy to local Ollama...
tailscale serve --bg http://127.0.0.1:11434
if errorlevel 1 (
  echo ERROR: Tailscale Serve setup failed. Right-click this file and choose
  echo Run as administrator, then try again.
  goto :failed
)

echo.
tailscale serve status
echo.
for /f "usebackq delims=" %%U in (`powershell.exe -NoProfile -Command "$s = tailscale status --json ^| ConvertFrom-Json; 'https://' + $s.Self.DNSName.TrimEnd('.')"`) do set "VSR_OLLAMA_URL=%%U"
if not defined VSR_OLLAMA_URL (
  echo ERROR: Could not determine this server's Tailscale HTTPS name.
  goto :failed
)

>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo %VSR_OLLAMA_URL%
>>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo.
>>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo Connectivity-only endpoints:
>>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo %VSR_OLLAMA_URL%/api/version
>>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo %VSR_OLLAMA_URL%/api/tags
>>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo.
>>"%~dp0VitalSignal-Ollama-Endpoint.txt" echo Do not send personal health data until the authenticated VitalSignal gateway is installed.

echo Phone connectivity test - no health data:
echo %VSR_OLLAMA_URL%/api/version
echo.
echo The endpoint was also saved here so no copy/paste is needed:
echo %~dp0VitalSignal-Ollama-Endpoint.txt
echo.
echo IMPORTANT: Restart Ollama before any later model test so local-only mode applies.
echo Direct personal health-data requests remain disabled until the authenticated
echo VitalSignal gateway and benchmark gates are installed.
echo.
pause
exit /b 0

:failed
echo.
echo Setup did not complete. No health data was sent.
pause
exit /b 1
