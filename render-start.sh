#!/usr/bin/env bash
set -euo pipefail

export SERVER_PORT="${PORT:-8080}"

URL="${SPRING_DATASOURCE_URL:-${DATABASE_URL:-}}"
case "$URL" in
  postgresql://*|postgres://*) export SPRING_DATASOURCE_URL="jdbc:$URL" ;;
  jdbc:postgresql://*|jdbc:*) export SPRING_DATASOURCE_URL="$URL" ;;
  "" ) echo "No DB URL found. Link your Render Postgres or set DATABASE_URL."; exit 1 ;;
  * ) export SPRING_DATASOURCE_URL="$URL" ;;
esac

# Helpful default so Hibernate doesn’t have to guess
export SPRING_JPA_DATABASE_PLATFORM="${SPRING_JPA_DATABASE_PLATFORM:-org.hibernate.dialect.PostgreSQLDialect}"

exec java -Dserver.port="$SERVER_PORT" -jar /app/app.jar
