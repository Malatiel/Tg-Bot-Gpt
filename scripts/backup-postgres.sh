#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
ENV_FILE="${ENV_FILE:-.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

mkdir -p "$BACKUP_DIR"

DB_NAME="${POSTGRES_DB:-tgbotgpt}"
DB_USER="${POSTGRES_USER:-postgres}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/${DB_NAME}-${STAMP}.dump"

docker compose exec -T db pg_dump -Fc -U "$DB_USER" "$DB_NAME" > "$OUT"
echo "Backup written to $OUT"
