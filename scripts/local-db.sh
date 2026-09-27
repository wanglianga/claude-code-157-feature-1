#!/usr/bin/env bash
# 本地开发数据库: 独立的 PostgreSQL 15 实例(端口 55432, 不依赖系统集群/免 sudo)
# 用法: ./scripts/local-db.sh init|start|stop|status|recreate
set -euo pipefail

PG_BIN="${PG_BIN:-/usr/lib/postgresql/15/bin}"
PG_DATA="$(cd "$(dirname "$0")/.." && pwd)/.local-pg/data"
PG_PORT="${PG_PORT:-55432}"
PG_LOG="$(dirname "$PG_DATA")/postgres.log"
DB_NAME="escort"
DB_USER="escort"
DB_PASS="escort"

cmd="${1:-status}"

case "$cmd" in
  init)
    if [ -d "$PG_DATA" ]; then
      echo "data dir already exists: $PG_DATA (use recreate to reset)"; exit 0
    fi
    mkdir -p "$PG_DATA"
    "$PG_BIN/initdb" -D "$PG_DATA" -U postgres --auth=trust --encoding=UTF8 >/dev/null
    echo "port = $PG_PORT" >> "$PG_DATA/postgresql.conf"
    echo "listen_addresses = 'localhost'" >> "$PG_DATA/postgresql.conf"
    echo "unix_socket_directories = '$(dirname "$PG_DATA")'" >> "$PG_DATA/postgresql.conf"
    "$PG_BIN/pg_ctl" -D "$PG_DATA" -l "$PG_LOG" -w start >/dev/null
    "$PG_BIN/psql" -h localhost -p "$PG_PORT" -U postgres -v ON_ERROR_STOP=1 \
      -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASS';" \
      -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;" >/dev/null
    echo "initialized: jdbc:postgresql://localhost:$PG_PORT/$DB_NAME (user=$DB_USER pass=$DB_PASS)"
    ;;
  start)
    "$PG_BIN/pg_ctl" -D "$PG_DATA" -l "$PG_LOG" -w start
    ;;
  stop)
    "$PG_BIN/pg_ctl" -D "$PG_DATA" -m fast stop
    ;;
  status)
    "$PG_BIN/pg_ctl" -D "$PG_DATA" status || true
    ;;
  recreate)
    "$PG_BIN/pg_ctl" -D "$PG_DATA" -m fast stop >/dev/null 2>&1 || true
    rm -rf "$(dirname "$PG_DATA")"
    "$0" init
    ;;
  *)
    echo "usage: $0 init|start|stop|status|recreate"; exit 1
    ;;
esac
