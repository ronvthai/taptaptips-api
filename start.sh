#!/usr/bin/env sh
set -e

URL="${SPRING_DATASOURCE_URL:-$DATABASE_URL}"

case "$URL" in
  postgresql://*|postgres://*) export SPRING_DATASOURCE_URL="jdbc:$URL" ;;
  jdbc:*) export SPRING_DATASOURCE_URL="$URL" ;;
  "" ) echo "No DB URL found. Set DATABASE_URL via render.yaml fromDatabase."; exit 1 ;;
  * ) echo "Using SPRING_DATASOURCE_URL as-is: $URL"; export SPRING_DATASOURCE_URL="$URL" ;;
esac

export SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT="${SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT:-60000}"
export SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION="${SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION:-true}"
export SPRING_SQL_INIT_MODE="${SPRING_SQL_INIT_MODE:-never}"

exec java -jar /app/app.jar
