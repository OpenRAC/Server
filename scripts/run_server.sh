#!/usr/bin/env bash
# Run the built server jar from the repository root. Build it first: ./gradlew :server:buildServerJar
# Settings come from .env.defaults and a local .env at the root; arguments override them as
# key value pairs (--port 9000).
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="services/server/build/libs/openrac-server-1.0.0.jar"
[ -f "$JAR" ] || { echo "Jar not found: $JAR, run ./gradlew :server:buildServerJar first." >&2; exit 1; }

# ZGC keeps pauses under a millisecond. Compact headers shrink every object.
FLAGS=(-XX:+UseZGC -XX:+UseCompactObjectHeaders)

exec java "${FLAGS[@]}" -jar "$JAR" "$@"
