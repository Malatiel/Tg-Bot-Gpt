# User Playbook

This playbook helps users get value from `telegram-Bot-Gpt` without learning the
implementation details. It focuses on repeatable workflows that already exist in
the bot: chat, private context, photos, documents, model choice, prompts, and
plan visibility.

## First Session

1. Send `/start` to see what the bot can do.
2. Send `/help` to see the command map.
3. Send `/settings` to check the current model, prompt, usage, and limits.
4. Ask a normal question in plain language.

Example:

```text
Help me draft a short reply to this message. Keep it polite and direct:
<paste the message>
```

## Daily Chat

Use the bot for small tasks that benefit from context and iteration:

- drafting replies;
- summarizing notes;
- comparing options;
- rewriting text in a different tone;
- explaining technical errors;
- turning rough ideas into checklists.

Good prompt shape:

```text
Goal: <what you want>
Context: <relevant facts>
Constraints: <tone, length, format, language>
Output: <bullets, table, draft, checklist, next steps>
```

Example:

```text
Goal: choose the best deployment option.
Context: small Telegram bot, one maintainer, PostgreSQL, Docker Compose.
Constraints: low maintenance, low cost, easy backup.
Output: compare 3 options and recommend one.
```

## Private Context

In private chat, the bot keeps recent conversation history. Use it for iterative
work:

```text
Make the previous draft shorter and more confident.
```

```text
Now translate it to Russian, but keep the technical terms in English.
```

Reset context when switching topics:

```text
/reset
```

Group chats are different: ask by mentioning the bot, and do not assume the same
private-chat memory behavior.

## Documents

Send a PDF or TXT file. Add a caption when you want a specific task.

Useful captions:

```text
Summarize the key decisions and risks in 10 bullets.
```

```text
Extract action items, owners, and deadlines. If an owner is missing, write
"unassigned".
```

```text
Review this document for contradictions, missing assumptions, and unclear terms.
```

```text
Turn this into a short executive summary and a detailed checklist.
```

Tips:

- Use TXT for large plain-text material when possible.
- If a PDF fails, try a smaller file or a text export.
- Treat the result as analysis, not as a signed legal or financial opinion.

## Images

Send a photo with an optional caption.

Useful captions:

```text
Describe what is visible and point out anything unusual.
```

```text
Extract the text you can read and summarize it.
```

```text
Review this screenshot and suggest what I should click or check next.
```

```text
Explain this chart for a non-technical audience.
```

Tips:

- Prefer clear screenshots or photos with readable text.
- Crop sensitive unrelated information before sending.
- For screenshots, mention the app or context in the caption.

## Model Choice

Use `/model` to pick from the configured models.

General guidance:

- Use the default lower-cost model for routine drafting, summaries, and quick
  questions.
- Use a stronger model for complex reasoning, ambiguous analysis, or important
  writing.
- Switch back after the expensive task if you want to conserve quota.

Check current model and limits:

```text
/settings
```

## Custom Prompt

Use `/prompt <text>` to set a personal system prompt.

Good custom prompts are short and behavioral:

```text
/prompt Answer in Russian unless I ask otherwise. Be concise, practical, and
challenge weak assumptions.
```

```text
/prompt Act as a senior Java/Spring reviewer. Lead with correctness risks and
test gaps. Avoid style-only comments.
```

Reset it:

```text
/prompt reset
```

Avoid putting secrets, tokens, passwords, or private credentials in prompts.

## Limits And Pro

Check balance:

```text
/balance
```

See available plans:

```text
/plan
```

Request Pro:

```text
/upgrade
```

Pro approval is owner-managed. The bot sends the owner your Telegram id and the
commands needed to review and approve the request.

## Better Results Checklist

- Say what you want done, not only the topic.
- Include enough context to make the answer useful.
- Specify output format when it matters.
- Ask for tradeoffs when deciding between options.
- Ask for risks and missing assumptions for important decisions.
- Use `/reset` before unrelated work.
- Check `/balance` before large document or image-heavy sessions.
