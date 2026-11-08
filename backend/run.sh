#!/usr/bin/env bash
# Runs the backend fat jar with the JVM flags Arrow needs.
# Usage: ./run.sh server | task EOD | seed-demo | migrate /path/to/legacy/data | adduser LOGIN PASSWORD
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${MYSTOCK_JAR:-$DIR/target/mystock-backend.jar}"
exec java --add-opens=java.base/java.nio=ALL-UNNAMED ${JAVA_OPTS:-} -jar "$JAR" "$@"
