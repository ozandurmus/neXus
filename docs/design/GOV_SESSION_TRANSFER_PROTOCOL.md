# GOV.SESSION.1 — Agent session-transfer packet

## Status

**DRAFT — PO REVIEW PENDING.** Not FROZEN. A tool-independent
session-boundary convention: a packet is informational and never
self-authorizing (§2). Reference implementation:
`scripts/gov_session_transfer.py` (stdlib only).

**Protocol version 2 (correction round 3, GOV.SESSION.1A):** version 1's
pointer-only packet (a bare `movement` id plus `refs`, no report content)
allowed a structurally valid but operationally incomplete handoff — a
session could close with a packet that named where the detail *should* be
without ever carrying the detail itself, splitting the report across an
out-of-band narrative and a minimal pointer. Version 2 folds the complete
`AI_START_HERE.md` "SESSION START"/"SESSION CLOSE" report into the packet
itself, as one nested `report` object, machine-checked field by field.
Version 1 packets are now rejected outright (`protocol_version` must be
`2`) — this is a breaking migration, not an additive one; removed
capabilities are not restorable without a new, explicit decision.

## 1. Problem

A coding/reasoning agent session needs to hand off one bounded movement to
another session or tool without pasting chat history, and without the
handoff itself being split across a packet (structure) and a separate
narrative message (content) that can drift apart or go missing. This
defines one self-delimiting JSON packet that carries the complete
SESSION START/SESSION CLOSE report plus a pointer to the repository files
that remain actually authoritative — never a copy of their content.

## 2. Design law

- **The repository stays authoritative; a packet is transport, never a
  record** — not `CURRENT_STATE.md`, `AI_HANDOVER.md`, or a
  `project/build_history.json` entry. `AGENTS.md` "Mandatory session
  start/close" and "Project-state update rule" apply regardless of whether
  a packet was rendered.
- **One packet, the whole report.** A version-2 packet's `report` object
  *is* the SESSION START/SESSION CLOSE report `AI_START_HERE.md` already
  requires — not a summary of it, not a pointer to where it "really"
  lives. `refs` stays a short list of repository-relative paths to the
  authoritative documents for the movement — reference, never a
  substitute for required report content.
- **Two message types, nothing else:** `SESSION_START`, `SESSION_CLOSE`.
- **Informational only.** Never grants an approval the repository does not
  already grant; never substitutes for `AI_START_HERE.md`'s reading order.
- **Structural validation proves structure, not truth.** A packet passing
  `validate`/`render` means its shape, required fields, and closed
  vocabularies are correct — it does not establish that any claim inside
  it (a test count, a merge state, a SHA) is actually true. The repository
  files a packet's `refs` point at remain the evidence of record.

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
is ignored. The envelope itself is unchanged from version 1.

## 4. Payload

### 4.1 Common top-level fields

`protocol_version` (must be `2`), `message_type` (`SESSION_START` |
`SESSION_CLOSE`), `movement` (short id, non-empty), `refs` (list of
repository-relative paths — non-empty strings; existence is not verified
by this script), `report` (object — schema below, depends on
`message_type`). `SESSION_CLOSE` additionally requires `outcome`, one of
`DONE` / `AUTOMATED_VALIDATED` / `PARTIAL` / `BLOCKED` (reused from
`AGENTS.md`'s own status vocabulary). `SESSION_START` must **not** carry
`outcome` at all. No field beyond this set is permitted at the top level —
an unknown top-level key is rejected, exactly like a missing mandatory one.

### 4.2 `report` schema — `SESSION_START`

Mirrors `AI_START_HERE.md` "SESSION START" one field at a time. Every
field below is mandatory (present, correctly typed); no other field is
permitted inside `report`.

| Field | Type |
| --- | --- |
| `baseline` | non-empty object (freeform — the authoritative product/engineering baseline this movement starts from; shape varies by movement, deliberately not schema-constrained) |
| `objective` | non-empty string |
| `scope.in` | non-empty list of non-empty strings |
| `scope.out` | non-empty list of non-empty strings |
| `movement_type` | one of `AGENTS.md`'s "Mandatory session start/close" list: `READ_ONLY_AUDIT`, `ARCHITECTURE`, `IMPLEMENTATION`, `VALIDATION`, `ROOT_CAUSE`, `UI`, `DOCS`, `RELEASE_HANDOVER` |
| `requirements` | non-empty list of non-empty strings |
| `acceptance_criteria` | non-empty list of non-empty strings |
| `validation_plan` | non-empty list of non-empty strings |
| `invariants` | non-empty list of non-empty strings |
| `risks` | list of non-empty strings (**may be empty** — "no risks" is a legitimate state, but the field itself must be present) |
| `context_not_loaded` | list of non-empty strings (may be empty) |
| `recommended_reasoning.tier` | non-empty string |
| `recommended_reasoning.reason` | non-empty string |
| `git.lane` | non-empty string |
| `git.base` | non-empty string |
| `merge_gate` | non-empty string |
| `deployment_direction` | one of `local validation only`, `staging-like`, `production-gated` |
| `output_contract` | non-empty list of non-empty strings |

### 4.3 `report` schema — `SESSION_CLOSE`

Mirrors `AI_START_HERE.md` "SESSION CLOSE" one field at a time. Every
field below is mandatory (present, correctly typed); no other field is
permitted inside `report`.

| Field | Type |
| --- | --- |
| `completed` | non-empty list of non-empty strings |
| `changed` | non-empty list of non-empty strings |
| `preserved` | non-empty list of non-empty strings |
| `validation.targeted` | non-empty string |
| `validation.affected` | non-empty string |
| `validation.full_regression` | non-empty string (e.g. `"not run, risk-based"`) |
| `validation.privacy` | non-empty string |
| `validation.state_consistency` | non-empty string |
| `validation.diff_check` | non-empty string |
| `validation.real_environment` | non-empty string (e.g. `"NOT_RUN"`) |
| `unresolved_risks` | list of non-empty strings (may be empty) |
| `state_updates` | list of non-empty strings (may be empty) |
| `next.movement` | non-empty string |
| `next.movement_type` | one of the `movement_type` values above |
| `next.status` | one of `utils/project_plan.py::STATUS_VALUES`: `done`, `in_progress`, `planned`, `blocked`, `deferred`, `complete`, `complete_with_followup`, `automated_validated`, `real_env_validated` |
| `next.objective` | non-empty string |
| `recommended_reasoning.tier` | non-empty string |
| `recommended_reasoning.reason` | non-empty string |
| `continuation` | one of `SAME_SESSION`, `NEW_SESSION` |
| `integration.branch` | non-empty string |
| `integration.head_sha` | non-empty string |
| `integration.pr` | integer or `null` |
| `integration.pr_url` | non-empty string or `null` |
| `integration.ci` | non-empty string |
| `integration.merge_state` | one of `NOT_OPENED`, `OPEN`, `MERGED`, `CLOSED` |
| `integration.merge_commit` | non-empty string or `null` |
| `integration.merge_decision` | non-empty string (e.g. `APPROVED_AND_INTEGRATED`, `BLOCKED_PENDING_PO_REVIEW`, `NOT_APPLICABLE`) |
| `effects.main_py` | non-empty string |
| `effects.ui` | non-empty string |

### 4.4 Rejected

Invalid JSON; a non-object payload; an unsupported `protocol_version` or
`message_type`; any missing mandatory field (top-level or nested, at any
depth); any unknown field (top-level or nested, at any depth); a value of
the wrong type; an empty value for a field that must be non-empty; a
closed-vocabulary field outside its allowed set; `SESSION_START` carrying
`outcome`; `SESSION_CLOSE` missing `outcome` or carrying an unknown one.
Syntactically valid JSON that is simply incomplete (mandatory trailing
fields never written) is rejected the same way as malformed JSON — both
end in no packet being emitted or accepted.

Full templates: `tests/test_gov_session_transfer.py`'s `_start_obj`/
`_close_obj` fixtures build one complete, currently-valid packet of each
type; that file is the executable form of this contract.

## 5. CLI

`scripts/gov_session_transfer.py` — stdlib only. Three operations:

```
py scripts/gov_session_transfer.py render   FILE|- [--out FILE]
py scripts/gov_session_transfer.py extract  FILE|-
py scripts/gov_session_transfer.py validate FILE|-
```

`render` reads one **bare** JSON object (no sentinel) from `FILE` or stdin
(`-`), validates it completely against the schema for its own
`message_type`, and only on success writes the sentinel-wrapped packet to
stdout (or `--out FILE`, never tracked automatically) — nothing is
written or printed on a validation failure. There is deliberately no
flag-driven path (no `start`/`close` subcommand building a packet field by
field): the only way to produce a packet is to hand `render` a complete,
already-assembled report object, so there is no code path capable of
emitting a report-less, version-1-shaped packet.

`extract` prints the one packet's JSON found in a larger text (a sentinel
pair already present in the input — e.g. pulled from a chat transcript).
`validate` prints `{"valid": bool, "errors": [...]}` for a sentinel-wrapped
input. Exit codes: `0` success, `1` invalid packet, `2` usage error. No
network, daemon, credential, or device access. See
`tests/test_gov_session_transfer.py` for the exact contract.
