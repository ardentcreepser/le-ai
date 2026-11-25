#!/bin/sh
# Start BobbyBot (macOS / Linux). Needs Java 25.
cd "$(dirname "$0")" || exit 1
exec java -jar BobbyBot.jar
