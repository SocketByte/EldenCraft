@echo off
setlocal
set "ELDENCRAFT_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if defined PROCESSOR_ARCHITEW6432 set "ELDENCRAFT_POWERSHELL=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%~dp0scripts\windows.ps1" (
  echo EldenCraft: scripts\windows.ps1 is missing beside this launcher.
  echo Extract the entire release ZIP first, then run EldenCraft.cmd from that folder.
  echo Keep the scripts, config and payload folders beside it. Check antivirus quarantine if extraction is complete.
  if "%~1"=="" pause
  exit /b 1
)
"%ELDENCRAFT_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows.ps1" %*
set "ELDENCRAFT_EXIT=%ERRORLEVEL%"
if not "%ELDENCRAFT_EXIT%"=="0" if "%~1"=="" pause
exit /b %ELDENCRAFT_EXIT%
