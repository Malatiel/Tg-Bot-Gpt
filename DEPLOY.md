# Deploy

This document describes how to run Tg-Bot-Gpt in production. For local
development see [README.md](README.md).

## Required environment

| Variable                     | Required           | Notes                                                                |
|------------------------------|--------------------|----------------------------------------------------------------------|
| `BOT_TOKEN`                  | yes                | Telegram bot token from @BotFather                                   |
| `OPENAI_APIKEY`              | yes                | OpenAI API key                                                       |
| `SPRING_PROFILES_ACTIVE`     | yes (`prod`)       | Activates the `prod` profile                                         |
| `SPRING_DATASOURCE_URL`      | yes                | JDBC URL, e.g. `jdbc:postgresql://db:5432/tgbotgpt`                  |
| `SPRING_DATASOURCE_USERNAME` | yes                |                                                                      |
| `SPRING_DATASOURCE_PASSWORD` | yes                |                                                                      |
| `ENCRYPTION_KEY`             | yes (in prod)      | base64-encoded 32-byte AES key. See "Encryption" below               |
| `ENCRYPTION_REQUIRED`        | optional (`true`)  | `true` in prod by default — startup fails if key missing/invalid     |
| `BOT_OWNER_IDS`              | yes (in practice)  | Comma-separated Telegram owner IDs. Owners run `/status` and `/admin`, and — since access is fail-closed — with an empty `BOT_WHITELIST` they are the only ones who can use the bot. If both are empty the bot rejects everyone. |
| `OPENAI_API_MODE`            | optional           | `responses` (default) or `chat`                                      |
| `OPENAI_MODEL`               | optional           | Default model; `gpt-5.4-nano` by default for lowest GPT-5.4 cost     |
| `OPENAI_ALLOWED_MODELS`      | optional           | Comma-separated models users can choose with `/model` buttons        |
| `OPENAI_MAX_HISTORY_TOKENS`  | optional           | Approximate context budget for DB history, default `2000`            |
| `BILLING_DEFAULT_PLAN`       | optional           | Plan for new users: `free` by default                                |
| `BILLING_FREE_MONTHLY_TOKENS` | optional          | Free-plan monthly token limit                                        |
| `BILLING_FREE_MONTHLY_MESSAGES` | optional        | Free-plan monthly message limit                                      |
| `BILLING_PRO_MONTHLY_TOKENS` | optional           | Pro-plan monthly token limit                                         |
| `BILLING_PRO_MONTHLY_MESSAGES` | optional         | Pro-plan monthly message limit                                       |
| `BILLING_PRO_DEFAULT_DAYS`  | optional           | Default Pro duration for owner approvals, `30` by default            |
| `BILLING_EXPIRATION_CLEANUP_CRON` | optional     | Cron for automatic expired-Pro cleanup                               |
| `BOT_WHITELIST`              | optional           | Comma-separated user IDs/usernames/group names; empty = owner-only (fail-closed) |

## Profiles

The `prod` profile differs from `dev` in several places:

| Setting                         | `dev`                                                | `prod`                                  |
|---------------------------------|------------------------------------------------------|-----------------------------------------|
| `spring.jpa.hibernate.ddl-auto` | `update`                                             | `validate`                              |
| Flyway                          | disabled by default (`SPRING_FLYWAY_ENABLED=true` to enable) | enabled                         |
| `encryption.required`           | `false`                                              | `true`                                  |
| Actuator endpoints              | `health, info, metrics`                              | `health` only, no details               |
| `server.shutdown`               | (default immediate)                                  | `graceful` (30s timeout)                |
| Logging                         | console + `GPTbot.log` file                          | console (stdout) only                   |

## Database migrations

Schema is owned by Flyway in prod. Migrations live under
`src/main/resources/db/migration` (`V1__init_schema.sql`, etc.).

**First-time prod against a Hibernate-managed DB.** If your existing DB was
created by `ddl-auto=update` (the dev path), Flyway will see a non-empty
schema with no `flyway_schema_history` table and refuse to start. Set
`SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` for the first prod boot:

```
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true SPRING_PROFILES_ACTIVE=prod ./run
```

After the first run a baseline row is recorded; you can drop the env var
afterwards.

CI runs every Flyway migration against a fresh PostgreSQL database. Tagged
releases are blocked if the clean migration check, Maven verification, or
Docker image build fails.

## Releases

Release tags must have a matching `CHANGELOG.md` section, for example
`## [0.4.0]`. The GitHub Release notes are generated from that section, and
the full `CHANGELOG.md` is uploaded as a release asset alongside the jar.

## Encryption

Generate a 256-bit AES key:

```bash
openssl rand -base64 32
```

Set it as `ENCRYPTION_KEY`. In prod, missing or invalid keys cause the app
to fail at startup (`encryption.required=true`).

**Key loss = data loss.** Messages encrypted with key X cannot be decrypted
with any other key. Store the key in a secrets manager (HashiCorp Vault, AWS
Secrets Manager, k8s `Secret`, etc.) and back it up alongside, but separately
from, your DB dumps. A dump without the key is partially unreadable.

The current scheme has no embedded key id, so **rotation requires re-encrypting
all stored messages**. Plan for this if you expect to rotate.

## Health and observability

Actuator listens on port `8081` bound to `127.0.0.1` by default. In prod the
exposure is restricted to `/actuator/health` only (no details).

```
curl http://127.0.0.1:8081/actuator/health
# {"status":"UP"}
```

If you deploy to Kubernetes, kubelet probes hit the pod IP — they will not
reach `127.0.0.1`. For k8s probes either:

- bind actuator to `0.0.0.0` and use a `NetworkPolicy` to restrict access; or
- use `exec` probes that run `curl` inside the container.

The bot exposes a built-in `OpenAiHealthIndicator`:

- **UP** — the most recent OpenAI call within `openai.health.freshness.seconds`
  (default 300s) succeeded.
- **DEGRADED** — the most recent call failed and the failure is fresh.
- **UNKNOWN** — no calls observed yet, or last call older than the freshness
  window.

The owner-only `/status` Telegram command surfaces aggregate, DB and OpenAI
status without exposing secrets.

OpenAI quota and rate-limit failures also trigger a Telegram alert to
`BOT_OWNER_IDS`.

## Graceful shutdown

On SIGTERM:

1. Telegram updates listener stops accepting new updates (pengrad's
   `removeGetUpdatesListener`).
2. The executor queue is drained for up to `bot.shutdown.timeout.seconds`
   (default 30s).
3. Anything still running is interrupted via `shutdownNow()`.

Updates that are still in Telegram's queue (not yet polled) and updates that
were rejected by the bounded executor (queue full) will be redelivered by
Telegram on next start — `UpdatesListener` returns the highest successfully
submitted `update_id`.

## Backpressure

The executor uses a bounded `ArrayBlockingQueue` of size
`bot.executor.queue.size` (default 128). When full, new updates are rejected
and Telegram is told via the listener return value to redeliver them on the
next long-poll. This trades latency for memory bound. Tune
`bot.executor.threads` and `bot.executor.queue.size` together — more threads
help latency, larger queue absorbs bursts at the cost of RAM.

## 429 retry tradeoff

`executeWithRetry` blocks the worker thread on `Thread.sleep(retry_after)`.
Under sustained 429 (e.g. spam from a single chat) every worker can end up
sleeping, the queue fills, and Telegram redelivers fresh updates instead of
the bot working through the existing ones.

To bound the damage:

- `bot.telegram.retry.max.backoff.ms` (default `10000`) caps the longest
  `retry_after` we will honour. Anything larger is logged as
  `tgbotgpt.telegram.retry` with `operation=<op>.skipped` and the request
  fails fast.
- Tune jointly with `bot.executor.threads` and `bot.executor.queue.size`. If
  you raise the cap, give yourself more threads.

A non-blocking scheduler-based retry would remove the worker-blocking
problem entirely; not implemented in 0.2.0.

## Resource limits

The Dockerfile sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
-XX:+ExitOnOutOfMemoryError`. Override at runtime for tiny instances:

```
docker run -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50" ...
```

## Usage plans

The bot has a billing-ready usage layer but no payment provider yet. Users are
assigned `free` by default, owners are treated as unlimited, and owners can
assign plans from Telegram:

```text
/balance
/plan
/upgrade
/admin approve <telegram_id> [days]
/admin extend <telegram_id> <days>
/admin downgrade <telegram_id>
/admin plan <telegram_id> <free|pro|owner> [days]
```

Limits reset automatically when the `YYYY-MM` usage period changes. Pro plans
expire at `plan_expires_at`; expired Pro users are downgraded to `free` during
usage checks and by the scheduled cleanup job.

## Backup and restore

Schema-only backup is meaningless without `ENCRYPTION_KEY` — chat history is
AES-256-GCM-encrypted at the application layer.

For Docker Compose deployments, use the included scripts:

```bash
./scripts/backup-postgres.sh
./scripts/restore-postgres.sh backups/tgbotgpt-2026-04-28.dump
```

Manual daily logical backup:

```bash
pg_dump -Fc -U postgres -h db tgbotgpt > tgbotgpt-$(date +%F).dump
```

Restore:

```bash
pg_restore -U postgres -h db -d tgbotgpt --clean --if-exists tgbotgpt-2026-04-25.dump
```

Always pair the dump with the matching `ENCRYPTION_KEY`. Store the key
separately from the dump to keep the threat model intact (loss of the dump
without the key leaks nothing).
