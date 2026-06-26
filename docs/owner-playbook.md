# Owner Playbook

This playbook is for the person operating `telegram-Bot-Gpt`. It covers the
manual operating model that exists today: trusted access, usage visibility, Pro
approval, expiry, and incident response. It does not assume payment automation.

## Operating Model

The recommended product posture is a private assistant for trusted users:

- access is fail-closed;
- owners bypass the whitelist;
- Free and Pro limits cap spend;
- users request Pro through the bot;
- owners manually review and approve Pro.

Do not present Pro as an instant purchase until pricing, payment, refunds, abuse
handling, and support ownership are decided.

## Access Control

The bot rejects users unless they are owners or appear in `bot.whitelist`.

Owner checklist:

- Keep `BOT_OWNER_IDS` set in every real deployment.
- Keep an empty whitelist only when owner-only mode is intentional.
- Add trusted users by Telegram id, username, or group title.
- Avoid opening the bot publicly until spend and abuse controls are reviewed.

Useful checks:

```text
/status
```

```text
/admin users
```

If both owners and whitelist are empty, the bot rejects everyone. That is safer
than accidental public exposure, but it looks like a broken bot to users.

## Pro Request Flow

User flow:

```text
/plan
/upgrade
```

Owner receives a message with review and approval commands.

Review:

```text
/admin usage <telegram_id>
```

Approve Pro for the default duration:

```text
/admin approve <telegram_id>
```

Approve Pro for a specific duration:

```text
/admin approve <telegram_id> 30d
```

Extend existing Pro:

```text
/admin extend <telegram_id> 15d
```

Downgrade back to Free:

```text
/admin downgrade <telegram_id>
```

Use explicit durations for paid or trial access. Avoid indefinite Pro unless the
user is an operator or trusted internal account.

## Plan Operations

Current plans:

- Free: monthly token and message limits.
- Pro: higher monthly token and message limits, with expiry.
- Owner: unlimited effective plan for configured owners.

Owner commands:

```text
/admin plan <telegram_id> free
/admin plan <telegram_id> pro 30d
/admin plan <telegram_id> owner
```

Before assigning `owner`, confirm the user should have unlimited access and
owner-level trust. Prefer Pro for normal paid or trial users.

## Weekly Review

Run this weekly while the bot is used by more than one person:

```text
/admin users
```

For heavy users:

```text
/admin usage <telegram_id>
```

Look for:

- unexpected new users;
- users near Free or Pro limits;
- expired or soon-to-expire Pro access;
- unusually high token usage;
- users asking for repeated manual support.

If usage grows, decide whether you need separate policies for documents and
images before raising limits.

## Quota And Rate-Limit Incidents

The bot notifies owners when OpenAI quota or rate-limit issues are detected.

Immediate steps:

1. Run `/status`.
2. Check OpenAI project billing and usage outside the bot.
3. Check recent heavy users with `/admin users` and `/admin usage <telegram_id>`.
4. Avoid increasing plan limits during the incident.
5. Communicate that requests may fail until provider quota recovers.

If Telegram sending is rate-limited, avoid rapid manual retries. The bot already
caps retry sleep to avoid starving worker threads.

## User Support Scripts

When a user asks what to do first:

```text
Send /help, then try one message, one document, and /settings. Use /balance to
check limits before larger files.
```

When a user hits Free limits:

```text
Send /balance to confirm usage, then /upgrade if you want Pro. I will review the
request and approve a time-limited Pro period if appropriate.
```

When a document fails:

```text
Try a smaller PDF, export to TXT, or add a clearer caption with the exact task.
```

When a user wants payment:

```text
Payments are not automated yet. Pro is currently owner-approved and time-limited.
```

## Before Automating Billing

Do not implement payment automation until these decisions are explicit:

- price and currency;
- trial policy;
- refund policy;
- expiration and grace-period behavior;
- fraud and abuse response;
- support channel and response expectations;
- whether document/image usage needs separate limits;
- whether payment records need a database migration.

Any payment provider integration, plan rule change, or billing schema change
should be planned and reviewed separately.

## Release Checklist For Product Changes

Before shipping user-visible product behavior:

- confirm commands and README agree;
- add or update tests when command behavior changes;
- run relevant Maven tests;
- check `git diff --check`;
- review that secrets, env files, and production config were not touched;
- update `CHANGELOG.md`.
