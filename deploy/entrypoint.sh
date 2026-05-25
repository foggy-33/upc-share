#!/bin/sh
set -eu

mkdir -p /app/resources /app/data /app/uploads
chown -R appuser:appuser /app/resources /app/data /app/uploads 2>/dev/null || true

exec su -s /bin/sh appuser -c "java $JAVA_OPTS -jar /app/app.jar"
