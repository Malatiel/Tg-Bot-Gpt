# Telegram Bot GPT

A Telegram bot powered by the OpenAI API. Supports private and group chats with per-user conversation context.

## Features

- Chat with OpenAI GPT models via Telegram
- **Streaming responses** — bot edits its message in real-time as tokens arrive
- **Per-user model selection** — each user can switch GPT models via `/model`
- **Rate limiting** — configurable per-user request limit (sliding window)
- Per-user conversation history (configurable pool size)
- Group chat support (mention the bot by name)
- Whitelist-based access control (by user ID, username, or group name)
- Token usage tracking (`/usage` command)

## Commands

| Command          | Description                              |
|------------------|------------------------------------------|
| `/start`         | Bot introduction                         |
| `/usage`         | Show total token count                   |
| `/reset`         | Reset conversation context (DM only)     |
| `/model`         | Show current model                       |
| `/model <name>`  | Switch to a different GPT model          |

## Tech Stack

- Java 22
- Spring Boot 3.3.1
- Spring WebFlux (WebClient for OpenAI API)
- [java-telegram-bot-api](https://github.com/pengrad/java-telegram-bot-api)
- Lombok
- Docker + PostgreSQL (optional)

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/Malatiel/Tg-Bot-Gpt.git
cd Tg-Bot-Gpt
```

### 2. Configure environment variables

Copy the example file and fill in your credentials:

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

**With Docker:**

```bash
docker compose up
```

**Without Docker:**

```bash
export BOT_TOKEN=your-telegram-bot-token
export OPENAI_APIKEY=your-openai-api-key
./mvnw spring-boot:run
```

## Configuration

All settings are in `src/main/resources/application.properties`:

| Property                       | Description                          | Default               |
|--------------------------------|--------------------------------------|-----------------------|
| `openai.model`                 | OpenAI model to use                  | `gpt-4o-mini`         |
| `openai.temperature`           | Response creativity (0.0 - 1.0)      | `0.7`                 |
| `openai.maxtokens`             | Max tokens per response              | `3000`                |
| `openai.max.message.pool.size` | Messages kept in user context        | `7`                   |
| `bot.whitelist`                | Comma-separated allowed user IDs/usernames (empty = allow all) | empty |
| `bot.rate.limit`               | Max requests per user per window | `10`                  |
| `bot.rate.window.seconds`      | Rate limit window in seconds     | `60`                  |
| `bot.stream.enabled`           | Enable streaming responses       | `true`                |
| `openai.allowed.models`        | Comma-separated allowed models   | `gpt-4o-mini,gpt-4o,gpt-4-turbo,gpt-3.5-turbo` |

## License

This project is for personal/educational use.
