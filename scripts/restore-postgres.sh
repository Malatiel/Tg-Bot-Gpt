#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 path/to/backup.dump" >&2
  exit 2
fi

BACKUP_FILE="$1"
ENV_FILE="${ENV_FILE:-.env}"

if [[ ! -f "$BACKUP_FILE" ]]; then
  echo "Backup file not found: $BACKUP_FILE" >&2
  exit 2
fi

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

DB_NAME="${POSTGRES_DB:-tgbotgpt}"
DB_USER="${POSTGRES_USER:-postgres}"

cat "$BACKUP_FILE" | docker compose exec -T db pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists
echo "Restored $BACKUP_FILE into $DB_NAME"
