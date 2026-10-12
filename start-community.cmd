@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch.ps1" -Action switch %*
if errorlevel 1 (pause) else (if "%~1"=="" pause)
