# MODEL_TIER_MAP.md — neutral tier to current model name

The neutral tier table (`AI_START_HERE.md` "Reasoning / model routing tiers")
is the durable rule. This is the only file that names a current model per
vendor; every other governance file refers to a tier by its neutral name
(`Fast/normal`, `Normal (strong)`, `High`) and never to a model name. Update
this table when the available model roster changes — no other file needs to
change with it.

| Tier | Claude Code | GitHub Copilot |
| --- | --- | --- |
| Fast/normal, Normal (strong) | Sonnet 5, normal | Sol (or equivalent normal-strong model) |
| High | Sonnet 5, extended thinking (high) | Terra High (or equivalent strongest reasoning mode) |
| PO — PLAN/REVIEW | Sonnet 5, normal | Sol |
| PO — DECIDE/DIRECTION_AUDIT | Sonnet 5, extended (high) | Terra High |
| Cross-subsystem architecture | Opus / Fast, only when warranted | strongest available reasoning mode |
| Council seat | lightest model/effort fitting the seat | same |

No model brand is a permanent default; the Product Owner selects the actual
provider, model, and effort per movement from scope, risk, contract state,
and available credit (`docs/reference/COPILOT_OPERATING_MODEL.md`).
