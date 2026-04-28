# Telegram Bot GPT

A Telegram bot powered by the OpenAI API. Supports private and group chats with per-user conversation context, persistent storage, image analysis, and document analysis.

## Features

- Chat with OpenAI GPT models via Telegram
- **PostgreSQL persistence** — user settings, chat history, and usage stats saved to DB
- **Custom system prompts** — each user can personalize bot behavior via `/prompt`
- **Image analysis** — send a photo and a vision-capable GPT model will describe/analyze it
- **Document analysis** — send a PDF or TXT file for GPT to analyze (with optional caption as instruction)
- **Streaming responses** — bot edits its message in real-time as tokens arrive
- **Per-user model selection** — each user can switch GPT models via `/model` buttons
- **Settings overview** — `/settings` shows model, prompt summary, usage, and limits
- **Rate limiting** — configurable per-user request limit (sliding window)
- **Message encryption** — AES-256-GCM encryption for chat messages in DB (optional)
- **Auto-cleanup** — old messages purged from DB after 30 days
- Per-user conversation history persisted in DB (survives restarts)
- Group chat support (mention the bot by name)
- Whitelist-based access control (by user ID, username, or group name)
- **Prompt injection protection** — blocks known LLM manipulation patterns
- Per-user usage tracking (`/usage` command)

## Commands

| Command            | Description                              |
|--------------------|------------------------------------------|
| `/start`           | Bot introduction                         |
| `/usage`           | Show your personal token/message stats   |
| `/reset`           | Reset conversation context + history     |
| `/status`          | Show service status (owner only)         |
| `/settings`        | Show model, prompt, usage, and limits    |
| `/model`           | Show model picker buttons                |
| `/model <name>`    | Switch to a different GPT model          |
| `/prompt <text>`   | Set a custom system prompt               |
| `/prompt reset`    | Reset prompt to default                  |
| *Send a photo*     | Image analysis (with optional caption)   |
| *Send a PDF/TXT*   | Document analysis (with optional caption) |

## Tech Stack

- Java 22
- Spring Boot 3.3.1
- Spring Data JPA + PostgreSQL
- Flyway database migrations
- Spring Boot Actuator (health, metrics)
- Spring WebFlux (WebClient for OpenAI API)
- [java-telegram-bot-api](https://github.com/pengrad/java-telegram-bot-api)
- Lombok
- Docker Compose
- H2 (for tests)

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/Malatiel/Tg-Bot-Gpt.git
cd Tg-Bot-Gpt
```

### 2. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env`:

```
BOT_TOKEN=your-telegram-bot-token
BOT_OWNER_IDS=your-telegram-user-id
OPENAI_APIKEY=your-openai-api-key
OPENAI_API_MODE=responses
OPENAI_MODEL=gpt-5.4-nano
OPENAI_ALLOWED_MODELS=gpt-5.4-nano,gpt-5.4-mini,gpt-4o-mini,gpt-4o
OPENAI_MAX_HISTORY_TOKENS=2000
SPRING_PROFILES_ACTIVE=dev
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your-postgres-password
POSTGRES_DB=tgbotgpt
ENCRYPTION_KEY=optional-base64-key
ENCRYPTION_REQUIRED=false
```

`.env`, `.env.local`, and other local env overrides are gitignored; only `.env.example` is meant to be committed.

### 3. Run

**With Docker (recommended):**

```bash
docker compose up --build
```

**Without Docker:**

```bash
export BOT_TOKEN=your-telegram-bot-token
export BOT_OWNER_IDS=your-telegram-user-id
export OPENAI_APIKEY=your-openai-api-key
export OPENAI_API_MODE=responses
export OPENAI_MODEL=gpt-5.4-nano
export OPENAI_ALLOWED_MODELS=gpt-5.4-nano,gpt-5.4-mini,gpt-4o-mini,gpt-4o
export OPENAI_MAX_HISTORY_TOKENS=2000
export SPRING_PROFILES_ACTIVE=dev
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/tgbotgpt
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=your-postgres-password
./mvnw spring-boot:run
```

Note: when running without Docker, you need a PostgreSQL instance running separately.
The default profile is `dev`, which uses `spring.jpa.hibernate.ddl-auto=update` for local development.

### 4. Enable message encryption (optional)

Generate a 256-bit key and add it to `.env`:

```bash
openssl rand -base64 32
```

```
ENCRYPTION_KEY=your-generated-key-here
```

Without this key the bot works normally but stores messages in plaintext.
Enabling encryption on an existing database is safe — old plaintext messages remain readable.
Set `ENCRYPTION_REQUIRED=true` to fail startup if the key is missing or invalid.

**Important:** do not lose the key. Messages encrypted with a lost key cannot be recovered.

### 5. Production profile

For production, run with `SPRING_PROFILES_ACTIVE=prod`. See [DEPLOY.md](DEPLOY.md) for the full runbook. The `prod` profile runs Flyway migrations, uses `spring.jpa.hibernate.ddl-auto=validate`, requires explicit datasource environment variables, requires encryption by default, logs to stdout only, and uses graceful shutdown.

Database migrations live in `src/main/resources/db/migration`. Flyway is disabled in `dev` (Hibernate manages the schema via `ddl-auto=update`); set `SPRING_FLYWAY_ENABLED=true` to opt in. When enabling `prod` against a database that was previously managed by Hibernate, set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` for the first run.

## Configuration

Common settings are in `src/main/resources/application.properties`; profile-specific database settings are in `application-dev.properties` and `application-prod.properties`.

| Property                       | Description                          | Default               |
|--------------------------------|--------------------------------------|-----------------------|
| `spring.profiles.default`      | Default Spring profile               | `dev`                 |
| `bot.owner.ids`                | Comma-separated Telegram user IDs allowed to run owner commands | empty |
| `openai.api.mode`              | OpenAI API mode: `responses` or `chat` | `responses`          |
| `openai.url`                   | OpenAI Chat Completions endpoint     | `https://api.openai.com/v1/chat/completions` |
| `openai.responses.url`         | OpenAI Responses API endpoint        | `https://api.openai.com/v1/responses` |
| `openai.model`                 | Default OpenAI model                 | `${OPENAI_MODEL:gpt-5.4-nano}` |
| `openai.temperature`           | Response creativity (0.0 - 1.0)      | `0.7`                 |
| `openai.maxtokens`             | Max tokens per response              | `3000`                |
| `openai.max.message.pool.size` | Recent messages loaded from DB as context | `7`                   |
| `openai.max.history.tokens`    | Approximate token budget for DB history loaded as context | `${OPENAI_MAX_HISTORY_TOKENS:2000}` |
| `openai.allowed.models`        | Comma-separated models users can choose via `/model <name>` | `${OPENAI_ALLOWED_MODELS:gpt-5.4-nano,gpt-5.4-mini,gpt-4o-mini,gpt-4o}` |
| `bot.whitelist`                | Allowed user IDs/usernames (empty = all) | empty             |
| `bot.rate.limit`               | Max requests per user per window     | `10`                  |
| `bot.rate.window.seconds`      | Rate limit window in seconds         | `60`                  |
| `bot.stream.enabled`           | Enable streaming responses           | `true`                |
| `encryption.key`               | AES-256 key, base64 (empty = disabled) | empty               |
| `encryption.required`          | Fail startup when encryption key is missing or invalid | `false` (`true` in `prod`) |
| `bot.document.max.size.mb`     | Max document file size in MB         | `10`                  |
| `bot.document.max.text.chars`  | Max extracted text chars sent to GPT | `15000`               |
| `bot.document.max.pages`       | Max PDF pages allowed for parsing    | `50`                  |
| `bot.document.parse.timeout.seconds` | PDF parsing timeout in seconds | `30`                  |
| `bot.file.download.timeout.seconds` | Connect/read timeout for Telegram file downloads | `15` |
| `bot.prompt.max.length`        | Max custom prompt length             | `500`                 |
| `bot.image.max.size.mb`        | Max image size in MB                 | `10`                  |
| `bot.image.allowed.types`      | Allowed MIME types for image analysis | `image/jpeg,image/png,image/gif,image/webp` |
| `bot.executor.threads`         | Update-handler thread pool size; `0` = #CPUs | `0`              |
| `bot.executor.queue.size`      | Bounded queue size for pending updates; rejected updates are redelivered by Telegram | `128` |
| `bot.shutdown.timeout.seconds` | Graceful shutdown timeout before `shutdownNow()` | `30`              |
| `bot.telegram.retry.max.backoff.ms` | Cap on Telegram 429 `retry_after` we'll honor; longer waits skip the retry. Sleeping blocks an executor worker — raise only if you have headroom | `10000` |
| `bot.history.retention.days`   | How long chat history is kept before nightly cleanup | `30`              |
| `bot.history.cleanup.cron`     | Spring cron expression for the cleanup job | `0 0 3 * * *`    |
| `openai.health.freshness.seconds` | Window after which an OpenAI outcome is considered stale by the health indicator | `300` |
| `management.server.port`       | Actuator HTTP port                   | `8081`                |
| `management.server.address`    | Actuator bind address                | `127.0.0.1`           |
| `management.endpoints.web.exposure.include` | Actuator endpoints exposed over HTTP | `health,info,metrics` (`health` in `prod`) |

## Security

- API keys and tokens are stored in `.env` (gitignored), never in source code
- All user input (prompts, usernames) is sanitized against control characters
- Prompt injection detection blocks known LLM attack patterns (jailbreaks, role overrides, instruction ignoring) in messages, captions, and custom prompts
- Document downloads restricted to HTTPS from exact host `api.telegram.org` only
- Document type, page count, size, and parsing timeout are validated before processing
- Image downloads restricted to HTTPS from exact host `api.telegram.org` only
- Image type and size validation before processing
- Telegram file downloads use explicit network timeouts
- Per-user rate limiting prevents abuse and budget overruns
- Optional AES-256-GCM encryption for chat messages in DB (protects against DB dump leaks)
- Production profile can require encryption at startup
- Automatic cleanup of old chat history (30-day retention)
- Whitelist support for restricting bot access
- Owner-only `/status` command reports runtime health (aggregate, DB, OpenAI) without tokens, keys, or user content
- Owners are notified when OpenAI quota or rate-limit errors are detected
- Actuator binds to `127.0.0.1:8081` by default; the `prod` profile exposes only `/actuator/health`
- Streaming OpenAI responses include usage accounting so `/usage` tracks token totals for streaming users

## Reliability

- **Backpressure.** Updates are processed through a bounded `ThreadPoolExecutor`. If the queue is full, the listener returns the last successfully submitted `update_id` and Telegram redelivers the rest on the next long-poll. No silent loss, no unbounded memory growth.
- **Graceful shutdown.** On SIGTERM the updates listener is removed first, the executor is given `bot.shutdown.timeout.seconds` to drain, then `shutdownNow()` is called. In-flight OpenAI calls have a chance to finish on every deploy.
- **Telegram 429 handling.** `sendMessage` and `editMessage` honour `retry_after` once. If the server asks for a wait longer than `bot.telegram.retry.max.backoff.ms`, the retry is skipped instead of blocking an executor thread on a guaranteed-to-fail repeat.
- **OpenAI health indicator.** A custom `openai` health component reports `UP`, `DEGRADED` (recent failure within the freshness window), or `UNKNOWN`. Surfaced via `/actuator/health` (when exposure permits) and the owner-only `/status`.
- **Streaming fallback.** If an OpenAI streaming response fails, the bot retries once with a non-stream completion without consuming another local rate-limit slot.
- **Structured logs.** Console logs include `request_id`, hashed Telegram user id, operation name, result, and duration for update handling.
- **Container-friendly defaults.** `prod` logs to stdout only; the Dockerfile sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`.

## Backup and restore

For Docker Compose deployments:

```bash
./scripts/backup-postgres.sh
./scripts/restore-postgres.sh backups/tgbotgpt-2026-04-28.dump
```

Backups are logical PostgreSQL dumps. If message encryption is enabled, keep the matching `ENCRYPTION_KEY` backed up separately from the dump.

See [DEPLOY.md](DEPLOY.md) for production runbook and [CHANGELOG.md](CHANGELOG.md) for the release log.

## License

This project is for personal/educational use.
