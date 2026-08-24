@echo off
setlocal EnableExtensions

echo Evidessa Research simulator - private server hosting
echo This publishes the simulated browser prototype inside your Tailnet only.
echo It does not start a health-data backend or clinical monitoring service.
echo.

where tailscale >nul 2>nul
if errorlevel 1 (
  echo ERROR: Tailscale is not installed or is not on PATH.
  goto :failed
)

set "VSR_SITE_FILE=%~dp0..\..\prototype\index.html"
if not exist "%VSR_SITE_FILE%" (
  echo ERROR: Prototype not found at:
  echo %VSR_SITE_FILE%
  goto :failed
)

tailscale status >nul 2>nul
if errorlevel 1 (
  echo ERROR: Tailscale is not connected on this PC.
  goto :failed
)

echo Configuring persistent Tailnet-only HTTPS at /evidessa ...
tailscale serve --bg --https=443 --set-path=/evidessa "%VSR_SITE_FILE%"
if errorlevel 1 (
  echo ERROR: Tailscale Serve setup failed.
  echo Right-click this file, choose Run as administrator, and try again.
  goto :failed
)

for /f "usebackq delims=" %%U in (`powershell.exe -NoProfile -Command "$s = tailscale status --json ^| ConvertFrom-Json; 'https://' + $s.Self.DNSName.TrimEnd('.') + '/evidessa'"`) do set "VSR_SITE_URL=%%U"
if not defined VSR_SITE_URL (
  echo ERROR: Could not determine this server's Tailscale HTTPS name.
  goto :failed
)

>"%~dp0Evidessa-Simulator-URL.txt" echo %VSR_SITE_URL%
>>"%~dp0Evidessa-Simulator-URL.txt" echo.
>>"%~dp0Evidessa-Simulator-URL.txt" echo Tailnet-only simulated research interface.
>>"%~dp0Evidessa-Simulator-URL.txt" echo No health backend or attended monitoring service is running.

echo.
tailscale serve status
echo.
echo Hosted successfully:
echo %VSR_SITE_URL%
echo.
echo The URL was saved to:
echo %~dp0Evidessa-Simulator-URL.txt
echo.
echo Open the URL from a device signed into the same Tailnet.
echo Future git pulls update the hosted file without reconfiguring Serve.
echo.
pause
exit /b 0

:failed
echo.
echo Hosting did not complete. Nothing was exposed publicly.
pause
exit /b 1
