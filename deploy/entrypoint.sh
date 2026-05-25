#!/bin/sh
set -eu

mkdir -p /app/resources /app/data /app/uploads
chown -R appuser:appuser /app/resources /app/data /app/uploads 2>/dev/null || true

JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then
  JAVA_BIN="$(command -v java)"
fi

exec su -s /bin/sh appuser -c "\"$JAVA_BIN\" $JAVA_OPTS -jar /app/app.jar"
