@echo off
rem 薄封装：把参数原样交给 scripts\mvn.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mvn.ps1" %*
exit /b %errorlevel%
