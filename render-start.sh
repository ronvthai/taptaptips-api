#!/usr/bin/env bash
set -euo pipefail

export SERVER_PORT="${PORT:-8080}"

URL="${SPRING_DATASOURCE_URL:-${DATABASE_URL:-}}"

to_jdbc() {
  local url="$1"
  # expect: scheme://user:pass@host:port/db?query
  local rest="${url#*://}"                  # user:pass@host:port/db?query
  local creds="${rest%%@*}"                  # user:pass
  local hostpath="${rest#*@}"                # host:port/db?query

  local user="${creds%%:*}"                  # user
  local pass="${creds#*:}"                   # pass

  local hostport="${hostpath%%/*}"           # host:port
  local dbq="${hostpath#*/}"                 # db?query

  local db="${dbq%%\?*}"                     # db
  local query=""
  if [[ "$dbq" == *\?* ]]; then
    query="${dbq#*?}"                        # query
  fi

  local host="$hostport"
  local port="5432"
  if [[ "$hostport" == *:* ]]; then
    host="${hostport%%:*}"
    port="${hostport#*:}"
  fi

  local jdbc="jdbc:postgresql://$host:$port/$db"
  local params="user=$user&password=$pass"
  if [[ -n "$query" ]]; then
    params="$params&$query"
  fi
  echo "$jdbc?$params"
}

case "$URL" in
  postgresql://*|postgres://*)
    export SPRING_DATASOURCE_URL="$(to_jdbc "$URL")"
    ;;
  jdbc:postgresql://*|jdbc:*)
    export SPRING_DATASOURCE_URL="$URL"
    ;;
  "" )
    echo "No DB URL found. Link your Render Postgres or set DATABASE_URL."; exit 1 ;;
  * )
    # If someone already provided a plain JDBC without creds, just use it as-is
    export SPRING_DATASOURCE_URL="$URL"
    ;;
esac

# Helpful default
export SPRING_JPA_DATABASE_PLATFORM="${SPRING_JPA_DATABASE_PLATFORM:-org.hibernate.dialect.PostgreSQLDialect}"

# (Optional, if you prefer env vars instead of URL query creds)
# export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-$user}"
# export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-$pass}"

exec java -Dserver.port="$SERVER_PORT" -jar /app/app.jar
