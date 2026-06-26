# Product Strategy

## Positioning

`telegram-Bot-Gpt` is a private AI assistant that lives where users already work:
Telegram. The strongest near-term product direction is a reliable personal
assistant for trusted users, with clear usage limits and owner-controlled Pro
access, rather than a broad public bot marketplace product.

## Product Hypotheses

- H1: Personal AI assistant in Telegram. The bot wins by being fast, familiar,
  private, and capable with chat, images, and documents. This matches the current
  codebase and can improve activation without schema or billing changes.
- H2: Paid power-user bot. Billing-ready limits, Pro requests, and admin approval
  already exist, but payment automation, pricing, and plan rules need explicit
  business decisions before implementation.
- H3: Team/workspace assistant. Group support exists, but workspace-level memory,
  roles, and shared administration would require larger architecture changes.

Chosen first direction: H1, while preserving H2 as the monetization path.

## Target Users

- Owners who run a private bot for themselves, family, or a small trusted group.
- Power users who want GPT in Telegram without switching apps.
- Users who frequently ask for document summaries, image analysis, drafting, and
  quick decision support.

Not the immediate target:

- Open public bot directories.
- Enterprise workspace administration.
- Fully automated subscription billing.

## Jobs To Be Done

- Ask quick questions in the same chat app used every day.
- Keep short private-chat context across restarts.
- Analyze a PDF, TXT file, or image without downloading extra tools.
- Choose a cheaper or stronger model depending on task value.
- Understand current plan limits before hitting spend ceilings.
- Let the owner approve trusted users for higher limits.

## Product Principles

- Reliability before novelty. Telegram delivery, OpenAI errors, and rate limits
  must be understandable and recoverable.
- Cost visibility by default. Users should know plan, limits, and remaining
  balance without owner intervention.
- Owner control first. Access remains fail-closed and upgrades remain owner
  approved until payment and abuse controls are designed.
- Small trusted-user UX beats broad anonymous growth.
- Avoid adding product promises that the bot cannot enforce technically.

## Near-Term Roadmap

### 0-30 Days: Activation And Clarity

- Make `/start` deterministic and free of OpenAI cost.
- Add `/help` as a compact map of core workflows.
- Keep `/settings`, `/balance`, `/plan`, and `/upgrade` easy to discover.
- Document the product direction and operator-facing decision points.
- Add tests around onboarding commands.

Success signals:

- New users can discover the bot's main capabilities without asking the owner.
- `/start` works even when OpenAI is down or quota-limited.
- Support questions about basic commands decrease.

### 30-60 Days: Retention And Power Usage

- Add task-oriented prompt examples in documentation.
- Consider lightweight saved prompt presets if usage shows repeated workflows.
- Improve admin visibility around active users and upgrade requests.
- Review whether document/image usage should have separate product limits.

Requires approval before implementation:

- Any schema migration.
- New persisted product state.
- Changes to billing limits or plan semantics.

### 60-90 Days: Monetization Decisions

- Decide whether Pro remains manual approval or moves to payment integration.
- Define pricing, refund policy, expiration rules, and abuse handling.
- Add owner-facing runbook for Pro operations.
- Only then consider payment provider integration.

Requires approval before implementation:

- Payment provider dependency.
- Public plan changes.
- Database migrations.
- Production configuration changes.

## Metrics

- Activation: users who send a successful first non-command message after
  `/start`.
- Engagement: weekly active users and messages per active user.
- Capability adoption: share of requests using text, image, and document flows.
- Cost safety: OpenAI tokens per active user and rate-limit/quota blocks.
- Monetization intent: `/plan` views and `/upgrade` requests.
- Reliability: failed Telegram sends, OpenAI errors, stream fallbacks, executor
  rejections.

## Risks

- OpenAI cost overrun if access control or monthly limits are loosened too early.
- Confusing Pro UX if upgrade requests are manual but presented like instant
  purchase.
- Prompt injection and untrusted document content remain best-effort controls.
- Blocking worker threads on Telegram retries can affect responsiveness during
  sustained rate limits.
- Broad group-chat behavior may surprise users because persistent context is
  private-chat focused.

## Current Implementation Gap

Before this strategy work, `/start` delegated the intro to OpenAI. That costs
tokens, depends on OpenAI health, and can fail during quota incidents. A static
onboarding response and a local `/help` command better match the product goal:
clear, reliable activation for trusted users.
