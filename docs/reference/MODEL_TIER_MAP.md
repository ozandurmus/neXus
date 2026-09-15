# MODEL_TIER_MAP.md — neutral tier to current model name

The neutral tier table (`AI_START_HERE.md` "Reasoning / model routing tiers")
is the durable rule. This file and the two vendor shims are the only places a
current model or provider may be named; every other governance file refers to
a tier by its neutral name and never to a model. Update this table when the
roster changes — no other file changes with it. Per-provider operating
behaviour is `docs/reference/PROVIDER_OPERATING_NOTES.md`, not here.

## Dispatch roster (Product Owner directive, 2026-09-15)

Supersedes the 2026-09-14 roster. The Product Owner took a Codex Pro licence on
2026-09-15 and named what each model is for; the four Codex entries below are
their words, not a derivation.

| Neutral name | Provider | Model | Effort | What it is for |
| --- | --- | --- | --- | --- |
| Main engineer | Codex | `gpt-5.6-terra` | `medium` | The standing dispatch. Ordinary bounded implementation |
| Strong implementation | Codex | `gpt-5.6-sol` | `medium`, `high` where argued | Strong code writing and reasoning |
| Architecture and feature design | Codex | `gpt-6-astra` | `medium`, `high` where argued | Architectural design, and high-level feature-level design and implementation |
| Micro edit | Codex | `gpt-5.3-spark` | `medium` | Minimal micro edits only. A movement that needs judgement is not this tier |
| Fallback dispatch | Claude | `claude-sonnet-5` | `medium` | Only after a Codex dispatch has failed twice for a reason that is not a defect in the packet |
| Not used | Codex | Luna | — | — |
| Trial, hybrid relay only | Antigravity | `pro`, `flash`, `flash_lite` | no effort flag exists | — |

Default down, not up (`AGENTS.md`, AI reasoning / movement routing): name the
lightest entry that covers the movement and state the reason when going above
it. Four entries now sit between "micro edit" and "architecture", so "it might
need more" is not a reason — the scope is.

The Product Owner assistant's own model is Claude. Codex is the standing
dispatch default because the Product Owner's Codex use is unmetered and their
Claude credit is not.

Antigravity joined as a third participant on 2026-09-15, in trial. It is not
dispatched by the orchestrator and is not a `--provider` value; it takes work
through the hybrid relay path and can hold either the engineer or the Product
Owner role. It reports no tokens and no cost, by measurement and by the
Product Owner's decision (`docs/reference/PROVIDER_OPERATING_NOTES.md`).

## Reasoning tiers

| Tier | Claude Code | Codex / Copilot |
| --- | --- | --- |
| Micro edit | — | Spark |
| Fast/normal, Normal (strong) | Sonnet 5, normal | Terra |
| High | Sonnet 5, extended thinking (high) | Sol |
| PO — PLAN/REVIEW | Sonnet 5, normal | Terra |
| PO — DECIDE/DIRECTION_AUDIT | Sonnet 5, extended (high) | Sol |
| Cross-subsystem architecture | Opus, only when warranted | Astra |
| Council seat | lightest model/effort fitting the seat | same |
