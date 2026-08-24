@echo off
setlocal EnableExtensions

echo Removing only the Evidessa /evidessa Tailnet route...
tailscale serve --https=443 --set-path=/evidessa off
if errorlevel 1 (
  echo ERROR: The route could not be removed.
  echo Right-click this file, choose Run as administrator, and try again.
  pause
  exit /b 1
)

echo.
echo Evidessa simulator hosting is stopped.
echo Other Tailscale Serve routes were not reset or changed.
pause
exit /b 0
