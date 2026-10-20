#!/bin/zsh
# Local backend for the integration review tree: a LOCAL Postgres database,
# never production. Refuses to start when anything on the classpath could point
# it at prod.
#
#   scripts/local-isolated.sh            # port 8090, DB sitprep_integration
#   PORT=8091 LOCAL_DB=other scripts/local-isolated.sh
#
# Why the checks exist: application.yml imports
# optional:classpath:application-local.yml in EVERY profile, and the shared
# tree's application-local.yml points at the production RDS cluster (P1 in
# SitPrep FE docs/guidelines/engineering/LAUNCH_READINESS.md).
#
# What this run can and cannot touch:
#   - database: local Postgres only; Flyway migrates it from V1
#   - scheduled jobs (alert ingest/dispatch, sweeps): off (app.scheduling.enabled=false)
#   - R2 uploads: placeholder credentials, so uploads fail instead of writing the real bucket
#   - pushes: FCM can only target device tokens stored in THIS database
#     (none until a local session registers one); APNs keys are unset
#   - drafts: visible via the allowlisted practice-preview profile; preview
#     Family Practice completions are never stored
set -eu
cd "${0:A:h}/.."

for f in src/main/resources/application-local.yml target/classes/application-local.yml; do
  if [ -e "$f" ]; then
    echo "REFUSING: $f exists. It carries production database settings." >&2
    exit 1
  fi
done
for v in JDBC_DATABASE_URL JDBC_DATABASE_USERNAME JDBC_DATABASE_PASSWORD; do
  if [ -n "${(P)v:-}" ] && [[ "${(P)v}" != *localhost* && "$v" == JDBC_DATABASE_URL ]]; then
    echo "REFUSING: $v is set to a non-local value in this shell." >&2
    exit 1
  fi
done
if [ ! -e src/main/resources/sitprep-new-firebase-adminsdk-june24.json ]; then
  echo "Missing the Firebase admin JSON (needed to verify sign-in tokens); symlink it from the main BE tree." >&2
  exit 1
fi

DB="${LOCAL_DB:-sitprep_integration}"
MIGS=(src/main/resources/db/migration/V*.sql)
MIGS=(${(f)"$(printf '%s\n' $MIGS | sort -V)"})
LATEST="${${MIGS[-1]:t}%%__*}"; LATEST="${LATEST#V}"
if createdb -h localhost "$DB" 2>/dev/null; then
  # A new DB is built by replaying the migrations through psql, as the
  # *PostgresIT tests do. Flyway itself can't build it from empty: V22 is a
  # CREATE INDEX CONCURRENTLY, which waits on Flyway's own lock until the
  # connection's statement_timeout cancels it (SYSTEM_TRAPS, Flyway CONCURRENTLY).
  echo "local-isolated: new DB $DB, replaying ${#MIGS} migrations through V$LATEST"
  for m in $MIGS; do psql -X -q -h localhost -d "$DB" -v ON_ERROR_STOP=1 -f "$m" >/dev/null; done
fi
# Flyway then baselines at the newest replayed version; later migrations apply normally.
export SPRING_FLYWAY_BASELINE_VERSION="$LATEST"

export JDBC_DATABASE_URL="jdbc:postgresql://localhost:5432/$DB"
export JDBC_DATABASE_USERNAME="$USER"
export JDBC_DATABASE_PASSWORD=""
export SPRING_PROFILES_ACTIVE="dev,practice-preview"
export SERVER_PORT="${PORT:-8090}"
export APP_SCHEDULING_ENABLED=false
export PRACTICE_INCLUDE_UNPUBLISHED=true
export R2_ACCOUNT_ID=local-disabled R2_ACCESS_KEY_ID=local-disabled R2_SECRET_ACCESS_KEY=local-disabled
export APP_FRONTEND_BASE_URL="http://localhost:3000"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}"

echo "local-isolated: db=$DB port=$SERVER_PORT profiles=$SPRING_PROFILES_ACTIVE"
exec ./mvnw -q spring-boot:run
