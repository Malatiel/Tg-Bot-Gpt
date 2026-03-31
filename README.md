# Telegram Bot GPT

A Telegram bot powered by the OpenAI API. Supports private and group chats with per-user conversation context, persistent storage, and image analysis.

## Features

- Chat with OpenAI GPT models via Telegram
- **PostgreSQL persistence** — user settings, chat history, and usage stats saved to DB
- **Custom system prompts** — each user can personalize bot behavior via `/prompt`
- **Image analysis** — send a photo and GPT-4o will describe/analyze it
- **Streaming responses** — bot edits its message in real-time as tokens arrive
- **Per-user model selection** — each user can switch GPT models via `/model`
- **Rate limiting** — configurable per-user request limit (sliding window)
- **Auto-cleanup** — old messages purged from DB after 30 days
- Per-user conversation history (configurable pool size)
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
| `/model`           | Show current model                       |
| `/model <name>`    | Switch to a different GPT model          |
| `/prompt <text>`   | Set a custom system prompt               |
| `/prompt reset`    | Reset prompt to default                  |
| *Send a photo*     | Image analysis (with optional caption)   |

## Tech Stack

- Java 22
- Spring Boot 3.3.1
- Spring Data JPA + PostgreSQL
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
OPENAI_APIKEY=your-openai-api-key
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your-postgres-password
```

### 3. Run

**With Docker (recommended):**

```bash
docker compose up
```

**Without Docker:**

```bash
export BOT_TOKEN=your-telegram-bot-token
export OPENAI_APIKEY=your-openai-api-key
./mvnw spring-boot:run
```

Note: when running without Docker, you need a PostgreSQL instance running separately.

## Configuration

All settings are in `src/main/resources/application.properties`:

| Property                       | Description                          | Default               |
|--------------------------------|--------------------------------------|-----------------------|
| `openai.model`                 | Default OpenAI model                 | `gpt-4o-mini`         |
| `openai.temperature`           | Response creativity (0.0 - 1.0)      | `0.7`                 |
| `openai.maxtokens`             | Max tokens per response              | `3000`                |
| `openai.max.message.pool.size` | Messages kept in user context        | `7`                   |
| `openai.allowed.models`        | Comma-separated allowed models       | `gpt-4o-mini,gpt-4o,gpt-4-turbo,gpt-3.5-turbo` |
| `bot.whitelist`                | Allowed user IDs/usernames (empty = all) | empty             |
| `bot.rate.limit`               | Max requests per user per window     | `10`                  |
| `bot.rate.window.seconds`      | Rate limit window in seconds         | `60`                  |
| `bot.stream.enabled`           | Enable streaming responses           | `true`                |
| `bot.prompt.max.length`        | Max custom prompt length             | `500`                 |
| `bot.image.max.size.mb`        | Max image size in MB                 | `10`                  |

## Security

- API keys and tokens are stored in `.env` (gitignored), never in source code
- All user input (prompts, usernames) is sanitized against control characters
- Prompt injection detection blocks known LLM attack patterns (jailbreaks, role overrides, instruction ignoring) in messages, captions, and custom prompts
- Image downloads restricted to HTTPS from `api.telegram.org` only
- Image type and size validation before processing
- Per-user rate limiting prevents abuse and budget overruns
- Automatic cleanup of old chat history (30-day retention)
- Whitelist support for restricting bot access

## License

This project is for personal/educational use.
