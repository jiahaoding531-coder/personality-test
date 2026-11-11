@echo off
powershell.exe -NoProfile -File "%~dp0Start-Lab.ps1"
if errorlevel 1 pause
