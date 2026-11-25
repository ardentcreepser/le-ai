@echo off
title BobbyBot
cd /d "%~dp0"
java -jar BobbyBot.jar
echo.
echo BobbyBot stopped. If you see "UnsupportedClassVersionError" above, install Java 25.
pause
