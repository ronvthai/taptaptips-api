#!/usr/bin/env bash
set -euo pipefail

# Render provides $PORT. Make Spring Boot bind to it.
export SERVER_PORT="${PORT:-8080}"

# Prefer DATABASE_URL if present (Render Postgres sets this).
URL="${SPRING_DATASOURCE_URL:-${DATABASE_URL:-}}"
case "$URL" in
  postgresql://*|postgres://*)
    # Render gives postgres://user:pass@host:5432/db
    export SPRING_DATASOURCE_URL="jdbc:$URL"
    ;;
  jdbc:postgresql://*|jdbc:*)
    export SPRING_DATASOURCE_URL="$URL"
    ;;
  "" )
    echo "No DB URL found; did you create a Render Postgres and link it?"; exit 1 ;;
  * )
    export SPRING_DATASOURCE_URL="$URL"
    ;;
esac

# Reasonable prod-ish defaults
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-}"
export SPRING_JPA_HIBERNATE_DDL_AUTO="${SPRING_JPA_HIBERNATE_DDL_AUTO:-validate}"
export SPRING_SQL_INIT_MODE="${SPRING_SQL_INIT_MODE:-never}"

# If you use Flyway, let it use SPRING_DATASOURCE_*
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"

# Start app; Gradle jar path:
if [ -f build/libs/*-SNAPSHOT.jar ] || [ -n "$(ls -1 build/libs/*.jar 2>/dev/null | head -n1)" ]; then
  exec java ${JAVA_TOOL_OPTIONS:-} -Dserver.port="$SERVER_PORT" -jar build/libs/*.jar
fi

# Maven jar path:
if [ -f target/*-SNAPSHOT.jar ] || [ -n "$(ls -1 target/*.jar 2>/dev/null | head -n1)" ]; then
  exec java ${JAVA_TOOL_OPTIONS:-} -Dserver.port="$SERVER_PORT" -jar target/*.jar
fi

echo "❌ Could not find a built jar in build/libs or target"; exit 2
