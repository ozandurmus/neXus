# GOV.SESSION.1 — Agent session-transfer packet

## Status

**DRAFT — PO REVIEW PENDING.** Not FROZEN. A minimal, tool-independent
session-boundary convention: a packet is informational and never
self-authorizing (§2). Reference implementation:
`scripts/gov_session_transfer.py` (stdlib only). Correction round 2
reduced this from an earlier, over-scoped design (versioned sentinel,
JSON Schema file, `git` object, transcript engine, size-policy machinery)
to exactly what is below — removed capabilities are not restorable without
a new, explicit decision.

## 1. Problem

A coding/reasoning agent session needs to hand off one bounded movement to
another session or tool without pasting chat history. This defines the
smallest artifact for that: a self-delimiting JSON packet naming a
movement and pointing at the repository files that are actually
authoritative — never a copy of their content.

## 2. Design law

- **The repository stays authoritative; a packet is transport, never a
  record** — not `CURRENT_STATE.md`, `AI_HANDOVER.md`, or a
  `project/build_history.json` entry. `AGENTS.md` "Mandatory session
  start/close" and "Project-state update rule" apply regardless of whether
  a packet was rendered.
- **Concise, reference over copy.** A movement id plus a short list of
  repository-relative paths (`refs`) — never repository history or broad
  project context.
- **Two message types, nothing else:** `SESSION_START`, `SESSION_CLOSE`.
- **Informational only.** Never grants an approval the repository does not
  already grant; never substitutes for `AI_START_HERE.md`'s reading order.

## 3. Envelope

```
<<<NEXUS_SESSION_PACKET>>>
{ ...one JSON object... }
<<<NEXUS_SESSION_PACKET>>>
```

The sentinel is the exact, permanent literal line `<<<NEXUS_SESSION_PACKET>>>`,
identical to open and close — no version, id, or timestamp in it. A line
matches only exactly, after stripping the line terminator (LF or CRLF).
Extraction requires **exactly one** sentinel pair anywhere in the input:
fewer than two lines is a missing/mismatched envelope; more than two is
rejected as multiple packet bodies (ambiguous). Content outside the pair
is ignored.

## 4. Payload

Common fields: `protocol_version` (must be `1`), `message_type`
(`SESSION_START` | `SESSION_CLOSE`), `movement` (short id, non-empty),
`refs` (list of repository-relative paths to the authoritative documents
for this movement — non-empty strings; existence is not verified by this
minimal script). `SESSION_CLOSE` additionally requires `outcome`, one of
`DONE` / `AUTOMATED_VALIDATED` / `PARTIAL` / `BLOCKED` (reused from
`AGENTS.md`'s own status vocabulary).

Rejected: invalid JSON, a non-object payload, an unsupported
`protocol_version` or `message_type`, and any missing mandatory field.

Templates (`SESSION_START`, then `SESSION_CLOSE`):

```json
{"protocol_version": 1, "message_type": "SESSION_START", "movement": "M8.3",
 "refs": ["docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md"]}
{"protocol_version": 1, "message_type": "SESSION_CLOSE", "movement": "M8.3",
 "outcome": "AUTOMATED_VALIDATED", "refs": ["project/build_history.json"]}
```

## 5. CLI

`scripts/gov_session_transfer.py` — stdlib only. Four operations:

```
py scripts/gov_session_transfer.py start   --movement ID --ref PATH [--ref PATH ...] [--out FILE]
py scripts/gov_session_transfer.py close   --movement ID --outcome OUTCOME --ref PATH [--ref PATH ...] [--out FILE]
py scripts/gov_session_transfer.py extract  FILE|-
py scripts/gov_session_transfer.py validate FILE|-
```

`start`/`close` print the sentinel-wrapped packet to stdout (or `--out`,
never tracked automatically). `extract` prints the one packet's JSON found
in a larger text. `validate` prints `{"valid": bool, "errors": [...]}`.
Exit codes: `0` success, `1` invalid packet, `2` usage error. No network,
daemon, credential, or device access. See
`tests/test_gov_session_transfer.py` for the exact contract.
