@echo off
title RuneAI dev client
cd /d "%~dp0"
git pull -q 2>nul
call gradlew.bat run
