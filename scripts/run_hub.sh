#!/usr/bin/env bash
# Run the built hub jar from the repository root. Build it first: ./gradlew :hub:buildHubJar
# Settings come from .env.defaults and a local .env at the root; arguments override them as
# key value pairs (--hub-port 9177).
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="services/hub/build/libs/openrac-hub-1.0.0.jar"
[ -f "$JAR" ] || { echo "Jar not found: $JAR, run ./gradlew :hub:buildHubJar first." >&2; exit 1; }

# ZGC keeps pauses under a millisecond. Compact headers shrink every object.
FLAGS=(-XX:+UseZGC -XX:+UseCompactObjectHeaders)

exec java "${FLAGS[@]}" -jar "$JAR" "$@"
