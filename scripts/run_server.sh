#!/usr/bin/env bash
# Run the built server jar from the repository root. Build it first: ./gradlew :server:buildServerJar
# Arguments go to the server (the port); DEBUG_LEVEL and DEBUGGING set the logging.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="services/server/build/libs/openrac-server-1.0.0.jar"
[ -f "$JAR" ] || { echo "Jar not found: $JAR, run ./gradlew :server:buildServerJar first." >&2; exit 1; }

# ZGC keeps pauses under a millisecond. Compact headers shrink every object.
FLAGS=(-XX:+UseZGC -XX:+UseCompactObjectHeaders)

exec java "${FLAGS[@]}" -jar "$JAR" "$@"
