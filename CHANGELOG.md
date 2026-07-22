# Changelog

All notable changes are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.9.2] — 2026-07-22 — Licence metadata

### Fixed
- The copyright holder's name is spelled consistently across `LICENSE`, its
  `Required Notice` line, and the README. The 0.9.1 artifacts carry the earlier
  spelling; the repository `LICENSE` governs either way.

## [0.9.1] — 2026-07-22 — Model lineup and licensing

### Added
- `gpt-5.6-luna` and `gpt-5.6-terra` as selectable models via `/model`. The
  default stays `gpt-5.4-nano`: the 5.6 tier costs roughly five times more per
  token, so it is opt-in rather than automatic.
- A `LICENSE` file (PolyForm Noncommercial 1.0.0) and `CONTRIBUTING.md` with a
  contributor license agreement.

### Fixed
- Image input detection matched only the `gpt-5.4` and `gpt-4o` prefixes, so a
  user on a `gpt-5.6` model was silently downgraded to the default model when
  sending a photo.
- The configured temperature is now omitted for `gpt-5.6` by default. Those
  reasoning models reject any temperature other than their own, which would
  have failed every `gpt-5.6` request with a 400.

### Changed
- Removed section-banner and code-restating comments from `GptService` and
  `TelegramBotService`; the banners no longer matched the code they labelled.

## [0.9.0] — 2026-07-12 — Trial conversion and payment operations

### Added
- Seven-day Pro trials for newly registered users, with trial-aware limits and
  automatic downgrade to Free after expiry.
- A daily trial-expiry notification sent approximately 24 hours before the
  trial ends, with a direct `/upgrade` call to action.
- Owner-only `/admin stats` analytics for trial conversion, Telegram Stars
  payments, and the most active users.
- Telegram Stars refund handling with idempotent payment status updates and
  safe Pro-plan downgrade behavior.

### Changed
- Telegram sending and admin command routing are split into focused components
  to keep transport, retry, and owner-command behavior independently testable.

## [0.8.0] — 2026-07-01 — Telegram Stars payments

### Added
- Telegram Stars native payment flow for Pro purchases directly inside
  Telegram with XTR-denominated invoices.
- Pre-checkout validation for Stars payments before Telegram confirms the
  invoice.
- Automatic Pro activation after successful Stars payment confirmation.
- Stored Telegram charge IDs for paid Pro purchases to prevent duplicate
  payment processing.

### Changed
- `/upgrade` now starts the Stars invoice flow and falls back to the manual
  owner-approval request when an invoice cannot be sent.
- The plan menu now offers both Stars purchase and manual Pro request paths.

## [0.7.0] — 2026-06-26 — Product onboarding and Pro admin UX

### Added
- Product strategy document covering positioning, target users, roadmap,
  metrics, and risks.
- User and owner playbooks with practical usage examples, manual Pro operations,
  access-control guidance, and incident-response checklists.
- `/help` command with a compact map of the bot's main workflows and commands.
- `/examples` command with Telegram-ready prompts for chat, documents, images,
  custom prompts, and limits.

### Changed
- `/start` now returns a deterministic local onboarding message instead of
  calling OpenAI, so first-run guidance works during API/quota incidents and
  does not spend tokens.
- Pro upgrade owner notifications now include current usage, remaining limits,
  expiry, review, approve, and keep-free commands.
- `/admin users` now shows usage limits and quick plan-management actions for
  each recent user.

## [0.6.1] — 2026-06-26 — Access control and OpenAI compatibility

### Changed
- **Access control is now fail-closed**: an empty `bot.whitelist` means owner-only
  access instead of open-to-all, so a deployment that forgets to configure a
  whitelist no longer exposes the bot (and the OpenAI key behind it) to everyone.
  A startup warning is logged when the whitelist is empty, and an error when both
  the whitelist and `bot.owner.ids` are empty (the bot then rejects all users).
- `pom.xml` version aligned with the changelog (`0.6.1`) so `/actuator/info`,
  `/status`, and release artifacts surface the intended version.
- Telegram update handling now records failed operations instead of letting
  unexpected runtime exceptions escape worker tasks.
- OpenAI requests can omit `temperature` for configured model prefixes that reject
  custom temperature values.
- `/admin users` now uses database ordering and limiting instead of loading all
  users before sorting.

### Security
- Encryption no longer silently falls back to storing plaintext when
  `encryption.required=true` and an encrypt operation fails — it now fails the
  operation instead.
- Prompt injection heuristics now also cover common Russian-language override and
  jailbreak phrasing (the bot is primarily Russian-speaking).
- Message and callback access checks now run before command handling, media
  downloads, user-state creation, or OpenAI calls.

## [0.6.0] — 2026-05-04 — Expiring subscriptions

### Added
- Expiring Pro subscriptions with `plan_expires_at` and a Flyway migration.
- Owner commands to approve, extend, and downgrade plans:
  `/admin approve`, `/admin extend`, and `/admin downgrade`.
- Scheduled cleanup for expired Pro users.

### Changed
- `/balance`, `/settings`, `/plan`, and `/admin users` now show plan expiry.
- Pro approvals now default to a limited duration instead of an indefinite
  assignment.

## [0.5.0] — 2026-04-30 — Plan UX and release reliability

### Added
- Billing-ready usage plans: `free`, `pro`, and `owner`.
- Monthly token/message counters and limits with automatic period reset.
- `/balance` and `/plan` commands.
- `/plans` and `/upgrade` user commands for plan discovery and Pro requests.
- Owner-only `/admin` command group with status, recent users, user usage, and
  plan assignment.
- Flyway migration for plan and monthly usage columns.
- CI Docker image build gate.
- CI Flyway migration check against a clean PostgreSQL database.

### Changed
- `/usage` now shows the richer balance summary.
- Owner plan assignment moved from `/plan set ...` to
  `/admin plan <telegram_id> <free|pro|owner>`.
- OpenAI requests are blocked before local rate-limit consumption when the
  user's monthly plan limit is already exhausted.
- Tagged releases now require a matching non-empty `CHANGELOG.md` version
  section, use it as GitHub Release notes, and upload the full changelog.

## [0.3.0] — 2026-04-28 — Bot UX and operations

### Added
- `/model` now opens inline buttons for model selection instead of requiring
  manual model-name entry.
- New `/settings` command showing current model, available models, prompt
  summary, usage, rate limit state, and history limits.
- Approximate history token budget via `OPENAI_MAX_HISTORY_TOKENS` /
  `openai.max.history.tokens`, in addition to the existing message-count
  limit.
- Streaming fallback: if an OpenAI stream fails, the bot retries once with a
  non-stream completion without consuming another local rate-limit slot.
- Owner alerts for OpenAI quota and rate-limit errors.
- Structured update logs with request id, hashed Telegram user id, operation,
  result, and duration.
- Docker app healthcheck against `/actuator/health`.
- PostgreSQL backup and restore scripts for Docker Compose deployments.
- `.env.prod.example` with production-oriented placeholders.

### Changed
- `/model` still accepts `/model <name>` for direct selection, but the default
  UX is now button-based.
- OpenAI quota/rate-limit failures now return an owner-notification message
  instead of the generic error text.

### Fixed
- Streaming fallback errors are now swallowed after the fallback response is
  sent, so a failed stream no longer bubbles up after the user receives the
  non-stream answer.
- Empty successful streams are no longer persisted as blank assistant messages
  in chat history.

## [0.2.0] — 2026-04-27 — Production hardening

### Added
- **Bounded update executor.** `TelegramBotService` now uses a
  `ThreadPoolExecutor` with an `ArrayBlockingQueue` (default size 128).
  Configurable via `bot.executor.threads` and `bot.executor.queue.size`.
- **Partial-ack for Telegram updates.** The `UpdatesListener` returns the
  last successfully-submitted `update_id`; on queue saturation the loop
  stops and Telegram redelivers unprocessed updates on the next long-poll.
- **Graceful shutdown.** On SIGTERM, the executor drains for
  `bot.shutdown.timeout.seconds` (default 30s) before `shutdownNow()`. The
  Telegram listener is removed first so no new work is accepted.
  `server.shutdown=graceful` is set in `prod`.
- **Telegram 429 retry.** `sendReply` and `editMessage` honor `retry_after`
  from Telegram and retry once, capped by `bot.telegram.retry.max.backoff.ms`.
- **OpenAI health indicator.** New `openai` health component reports `UP`,
  `DEGRADED` (recent failure within `openai.health.freshness.seconds`), or
  `UNKNOWN`. Surfaced in owner-only `/status`.
- **Configurable history retention.** `bot.history.retention.days` (default
  30) and `bot.history.cleanup.cron` (default `0 0 3 * * *`).
- **Container-friendly defaults.** Stdout-only logging in `prod`. Dockerfile
  sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`.
- **DEPLOY.md** — production runbook covering env, profiles, Flyway baseline,
  encryption, health, shutdown, backpressure, and backup/restore.
- New metrics: `tgbotgpt.executor.rejected`, `tgbotgpt.telegram.retry`.

### Changed
- Default model is now configurable via `OPENAI_MODEL` and defaults to
  `gpt-5.4-nano`, the lowest-cost GPT-5.4-class model.
- Allowed user-selectable models are now configurable via
  `OPENAI_ALLOWED_MODELS` and include `gpt-5.4-nano` and `gpt-5.4-mini`.
- `/model` now shows the available model list when displaying the current
  user selection.
- `BotStatusService` no longer opens its own DB connection; DB and OpenAI
  status are now read from the actuator `HealthEndpoint`. The `/status`
  output gains an `OpenAI:` line.
- `GptService.getCompletion`/`getCompletionStream` collapse client selection
  into helpers and route success/error signals to the OpenAI health
  indicator.
- `ChatHistoryService.cleanupOldMessages` cron is now configurable.

### Fixed
- Docker builds now clear the Maven image's default `MAVEN_CONFIG` before
  invoking the project wrapper, preventing `/root/.m2` from being parsed as a
  Maven lifecycle phase.
- Docker Compose now uses PostgreSQL 16 and a project-specific database volume
  to avoid reusing incompatible local Postgres data directories.
- Responses API streaming now ignores non-text service events without failing
  the Reactor pipeline.
- Responses API streaming now treats `error` and `response.failed` events as
  OpenAI client errors instead of producing an empty Telegram response.
- `TelegramBotService.dispose()` previously returned immediately on
  `executorService.shutdown()`, dropping in-flight requests on every deploy.
  Now waits up to the configured timeout, then forces.
- `dispose()` now closes the Telegram HTTP client **after** the executor has
  drained — earlier order would have failed `sendReply` / `editMessage` /
  `GetFile` calls still running in the queue.
- `OpenAiHealthIndicator` now reports `UNKNOWN` when the most recent
  successful call is older than `openai.health.freshness.seconds`. Previously
  a stale success was always reported as `UP`.
- Default `bot.telegram.retry.max.backoff.ms` lowered from `30000` to
  `10000`. The retry blocks an executor worker; the looser cap risked
  starving the pool under sustained 429s. Tune up if you give the executor
  more threads. See [DEPLOY.md](DEPLOY.md#429-retry-tradeoff) for the
  tradeoff.

## [0.1.1] — 2026-04-25 — Review fixes

### Added
- Spring Boot Actuator (`health`, `info`, `metrics`) on `127.0.0.1:8081`.
  In `prod` only `health` is exposed without details.
- Owner-only `/status` Telegram command exposing uptime, version, API mode,
  default model, streaming flag and DB/aggregate status.
- `BOT_OWNER_IDS` config; owners now bypass `bot.whitelist`.
- Streaming usage capture: `stream_options: {include_usage: true}` for the
  Chat Completions API; parses `response.completed` for the Responses API.
  Token counters now grow for streaming users.
- New metrics for OpenAI requests/errors and Telegram send/edit, all with
  PII-free tags only.

### Changed
- Default `openai.api.mode` driven by `${OPENAI_API_MODE:responses}`.
- WebClient cached at `@PostConstruct` in both OpenAI clients (was rebuilt
  per call).
- `metrics.recordOpenAiRequest` moved to `doOnSubscribe` (was incrementing
  on Mono creation regardless of subscription).
- `BotStatusService` reads version from `BuildProperties` (build-info goal
  added to the Spring Boot Maven plugin).

## [0.1.0] — 2026-04-25 — Initial hardened release

Initial public release of the Telegram bot. See
[README.md](README.md) for feature overview.
