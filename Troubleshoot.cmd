@echo off
setlocal
set "ELDENCRAFT_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if defined PROCESSOR_ARCHITEW6432 set "ELDENCRAFT_POWERSHELL=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%~dp0scripts\diagnostics.ps1" (
  echo EldenCraft: scripts\diagnostics.ps1 is missing.
  echo Extract the entire release ZIP first, then run Troubleshoot.cmd from that folder.
  if "%~1"=="" pause
  exit /b 1
)
"%ELDENCRAFT_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\diagnostics.ps1" %*
set "ELDENCRAFT_EXIT=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %ELDENCRAFT_EXIT%
