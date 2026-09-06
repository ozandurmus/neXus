# GOV.SESSION.1 — Agent session-transfer protocol

## Status

**DRAFT — PO REVIEW PENDING.** Not FROZEN. This movement authorizes exactly
what it ships: the envelope/schema below, `scripts/gov_session_transfer.py`
as its reference implementation, and their tests. It authorizes no future
packet's *content* — a packet is transport, never an authority (§2).

Revision 2 superseded the free-text, versioned-sentinel design this movement
started with (a per-packet `packet_id` embedded in the sentinel line itself,
and a compact `KEY: value` text payload) with the permanent literal sentinel
and strict JSON payload below. Revision 3 (this document, final-acceptance
correction round) added the required `git` object (§4), split the payload
size ceiling per message type (§4), made the packet the canonical
chat-transport form of `SESSION START`/`SESSION CLOSE` rather than an
addition alongside a separate narrative (§2, §5), and made this schema
document normative rather than reference-only (§4) — a CLI/schema parity
test in `tests/test_gov_session_transfer.py` enforces the last of these.

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE` (contract) + `IMPLEMENTATION` (reference CLI), one bounded build, no product/vendor/security-boundary change |
| **Base** | `main == origin/main` at the commit this branch forked from |
| **Reference implementation** | `scripts/gov_session_transfer.py` (stdlib only — no network, daemon, scheduler, LLM call, credential or device access) |
| **Schema** | `docs/reference/gov_session_transfer_packet.schema.json` |
| **Examples** | `docs/reference/gov_session_transfer_session_start.example.json`, `docs/reference/gov_session_transfer_session_close.example.json` |
| **Preserves unchanged** | `AGENTS.md` authority hierarchy, movement types, build lifecycle and status vocabulary; `AI_START_HERE.md` reading order and `SESSION START`/`SESSION CLOSE` schemas; `AI_HANDOVER.md`/`CURRENT_STATE.md` roles. This protocol adds a chat-transport envelope on top of them — it creates no competing state authority |
| **Out of scope** | `M8.3` and every frozen `M8` law — untouched by this movement |

---

## 1. Problem

A coding/reasoning agent session (Claude, Codex, Copilot, or a local script)
frequently needs to hand off **one bounded movement** to another session —
the same tool resuming after a compaction, a different tool picking up the
same branch, or a human relaying the next step by pasting text into a fresh
chat. Re-pasting chat history is expensive, lossy, and tempts the receiver
into treating chat as authoritative. This protocol defines the smallest
artifact that closes that gap: a self-delimiting, machine-checkable
**packet**, generated and parsed by nothing but a stdlib script, naming a
repository, a movement, and pointers back into the repository's own
authority chain — never a copy of that chain's content.

## 2. Design law

- **The repository stays authoritative; a packet is transport, never a
  record.** A packet is not `CURRENT_STATE.md`, not `AI_HANDOVER.md`, not a
  `project/build_history.json` entry. Citing one does not satisfy
  `AGENTS.md`'s "Mandatory session start/close" or "Project-state update
  rule" — those still happen, in the repository, exactly as before. A
  generated packet file is transient and untracked by default (§4); nothing
  in this protocol adds a file to Git.
- **One packet = one movement.** A packet's `movement` field names exactly
  one movement; a packet is not a roadmap, a multi-build plan, or a standing
  instruction.
- **Compact by default, reference over copy.** A packet carries identifiers
  and repository-relative paths (`authority_refs`, `refs`), not copied
  contract text or project history. Bounded sizes are enforced, not merely
  requested (§4).
- **Two message types, nothing else.** `SESSION_START` opens a movement;
  `SESSION_CLOSE` closes it. There is no third type. A valid packet *is* the
  canonical chat/session-transport form of `AI_START_HERE.md`'s
  `SESSION START`/`SESSION CLOSE` requirement — an agent that renders one
  does not also owe a separate, duplicate unstructured prose narrative
  before or after it in chat. This replaces chat narrative only: the
  durable repository updates `AGENTS.md` "Mandatory session start/close" and
  "Project-state update rule" require (`CURRENT_STATE.md`,
  `AI_HANDOVER.md`, `project/*.json`, phase docs as applicable) remain
  mandatory and authoritative regardless of whether a packet was rendered.
- **Session routing is explicit, not inferred.** `SAME` (in `SESSION_START.
  session_mode` or `SESSION_CLOSE.session_routing`) means the movement
  continues in the current session/branch — a correction round, a merge
  follow-up — exactly as `AI_START_HERE.md` "Context/token discipline"
  already describes ("same build continues, keep the chat"). `NEW` means the
  movement is closed and the objective changes; the receiving session starts
  cold from the repository's normal reading order, never from chat.
- **Model/reasoning hints are advisory and vendor-neutral.** An optional
  `model_hint` may echo `AGENTS.md` "AI reasoning / movement routing" tier
  language (e.g. `"high reasoning: new architecture"`). It never names a
  vendor model ID and never binds the receiving tool.
- **Permissions travel with the packet; they never loosen the repository's
  own gates.** `SESSION_START.allow` / `.deny` / `.stop_on` restate or
  narrow `AI_START_HERE.md` "Git workflow" for this specific movement. A
  packet may add a stricter local rule; it can never claim an approval the
  repository does not already grant (a packet cannot itself authorize a Git
  push/merge, a schema migration, or a `CLASS 2+` action).
- **No standing infrastructure.** The protocol and its CLI are offline,
  synchronous, single-invocation tools: no network call, no daemon/service,
  no scheduler, no local-LLM/orchestration, no automatic merge, no device
  contact, no credential use, and no automatic execution of a packet's
  *next* movement.
- **One discovery path, not two.** `AI_START_HERE.md` gains one pointer to
  this document (§6); it does not gain a second cold-start reading order.

## 3. Envelope

Every packet is wrapped in the same literal line, used identically to open
and close:

```
<<<NEXUS_SESSION_PACKET>>>
{ ...strict JSON object... }
<<<NEXUS_SESSION_PACKET>>>
```

- The sentinel is **permanent and version-independent**: exactly
  `<<<NEXUS_SESSION_PACKET>>>`, a complete line with no leading or trailing
  whitespace. It never carries a version, packet id, message type,
  movement, timestamp, model or vendor — those all live inside the JSON
  payload instead, so the envelope never needs to change when the payload
  schema gains a new field or version.
- A line is a sentinel line only on an **exact** match after stripping the
  line terminator — no case-insensitivity, no surrounding-whitespace
  tolerance, no alternative spelling. A line reading `<<<NEXUS_SESSION_PACKET>>> `
  (trailing space) or `<<<nexus_session_packet>>>` is ordinary content, not a
  boundary.
- Input is UTF-8 without a byte-order mark, with ordinary LF or CRLF line
  endings; both are accepted identically (compare after stripping the
  terminator, not the raw bytes).
- **Standalone validation** (one packet, nothing else) requires **exactly
  two** sentinel lines in the input, in that order, with nothing but
  whitespace outside the pair — before the first sentinel line and after the
  second.
- **Transcript extraction** (a packet embedded in a larger message) scans
  every sentinel-line occurrence and pairs them **strictly in sequence**:
  1st+2nd line form pair 0, 3rd+4th form pair 1, and so on. Every enclosed
  object is parsed and validated independently — one malformed packet in a
  transcript does not block extracting a different, valid one. An **odd**
  total sentinel-line count cannot form complete pairs and fails the whole
  extraction closed. Because the sentinel is one literal line reused for
  both roles, two boundaries can never "nest" under this pairing rule by
  construction — the failure mode that a naive open/close-stack
  implementation would call nesting instead surfaces here as an **empty
  pair** (two sentinel lines with no payload line between them), which
  fails that specific pair closed without aborting the scan (`tests/test_gov_session_transfer.py`
  proves both the odd-count and the empty-pair case).
- The literal text `<<<NEXUS_SESSION_PACKET>>>` must never occur anywhere
  inside a payload — including inside a JSON string value. A reader checks
  for this byte-for-byte before attempting to parse the payload as JSON.

## 4. Payload contract

**Normative**, alongside this document:
`docs/reference/gov_session_transfer_packet.schema.json` (JSON Schema,
`oneOf` branch selected by `message_type`, `additionalProperties: false` on
both branches and on the nested `git` object). `scripts/gov_session_transfer.py`
is the conforming reference implementation: dependency-free (it does not
load a `jsonschema` library at runtime), it enforces every constraint below
natively in Python. `tests/test_gov_session_transfer.py` proves material
parity between the two — required-field sets, `additionalProperties`, and
every closed-vocabulary enum are asserted equal — so schema and
implementation cannot silently diverge. The one constraint the schema
cannot express as a field-level JSON Schema keyword is the whole-payload
byte ceiling below (a serialized-size limit); that stays documented here
and in the schema's own top-level `description`, enforced only by the CLI.

**Size ceilings** (reject before/while parsing, not merely by convention).
The payload ceiling is **per message type**, not one shared number:

| Bound | Value |
| --- | --- |
| `SESSION_START` whole payload (UTF-8 bytes, between the sentinel lines) | 4096 |
| `SESSION_CLOSE` whole payload (UTF-8 bytes, between the sentinel lines) | 8192 |
| `packet_id` / `project` / `movement` | ≤ 80 chars, `^[A-Za-z0-9][A-Za-z0-9_.-]*$` |
| Short free-text field (`objective`, `ui_effect`, `outcome`, `next_movement`, `model_hint`, …) | ≤ 240 chars |
| `validation` (test-evidence summary) | ≤ 600 chars |
| Any list (`authority_refs`, `allow`, `deny`, `stop_on`, `changed`, `preserved`, `refs`, `risks`, `durable_state`) | ≤ 24 items, each ≤ 300 chars |

A payload's `message_type` is not known until it is parsed, so enforcement
is two-stage: a coarse, type-agnostic gate at the larger of the two values
(8192 bytes) runs first and cheaply rejects worst-case input before
parsing; the exact per-type ceiling is then enforced the moment
`message_type` is known (`scripts/gov_session_transfer.py::check_type_specific_size`,
called from both `parse_and_validate` and `render_packet`). Both the exact
boundary and one byte over it are tested for both message types.

**Common fields (both message types):** `protocol_version` (integer, exactly
`1` in this movement — `true`/`false`, a string, `0`, a negative number or
any other integer fail closed as unsupported), `packet_id`, `message_type`
(`SESSION_START` | `SESSION_CLOSE`, closed vocabulary), `project`,
`movement`, `git` (below).

**`SESSION_START` additionally requires:** `session_mode` (`SAME` | `NEW`),
`objective`, `authority_refs` (repository-relative paths this packet's
claims depend on — `validate --repo-root` checks each one exists; existence
only, never content), `allow`, `deny`, `stop_on`, `close_required`
(boolean). Optional: `model_hint`.

**`SESSION_CLOSE` additionally requires:** `outcome` (one of `AGENTS.md`'s
own status-progression vocabulary: `AUTOMATED_VALIDATED` |
`REAL_ENV_VALIDATED` | `DONE` | `PARTIAL` | `BLOCKED` — reused, not
reinvented), `changed`, `preserved`, `validation`, `privacy_state`
(`PASS` | `FAIL` | `NOT_APPLICABLE` | `UNKNOWN`), `refs`, `risks`,
`durable_state`, `next_movement` (string or `null`), `session_routing`
(`SAME` | `NEW`), `ui_effect`. Optional: `model_hint`.

**`git` object (required on both message types, `additionalProperties: false`):**
the machine-readable position of this packet in the repository's own Git
history — never a substitute for the repository state itself, only a
pointer to it.

- `SESSION_START.git`: `base_sha` (lowercase hex, 7–40 chars — the commit
  the branch forks from), `branch` (the working branch for this movement).
- `SESSION_CLOSE.git`: `base_sha`, `branch`, `head_sha` (lowercase hex),
  `pr_number` (positive integer or `null` — a direct-to-`main` movement with
  no PR is legitimate), `merged` (boolean), `merge_sha` (lowercase hex or
  `null`). SHA/null consistency is enforced, not merely typed: `merge_sha`
  is required (non-`null`) when `merged` is `true`, and must be `null` when
  `merged` is `false` — both directions are tested.

Strict-JSON rules enforced ahead of field validation: no duplicate object
keys, no `NaN`/`Infinity`/`-Infinity` tokens, no trailing non-whitespace
content after the JSON value, top-level value must be a JSON object (not an
array, string or number).

## 5. What a receiving agent does with a packet

1. Extract the payload (`scripts/gov_session_transfer.py extract`, or
   `validate` for a standalone packet) — refuse per §3/§4 on any malformed
   packet before reading its content as instruction.
2. For `SESSION_START`: verify the named `movement`/`authority_refs` against
   the actual checkout before acting — a packet's claim that disagrees with
   the repository is reported, never silently reconciled (`AGENTS.md`
   "Authority hierarchy").
3. Follow the normal `AI_START_HERE.md` reading order for the movement. The
   packet is a pointer into that order, not a replacement for it.
4. Do the movement, honoring `allow`/`deny`/`stop_on` as an additional,
   narrower constraint on top of the repository's own approval boundaries —
   never as an expansion of them.
5. Close the movement the normal way: update durable project state
   (`AGENTS.md` "Project-state update rule") and rewrite `AI_HANDOVER.md` —
   those are unaffected by this protocol and remain mandatory. When
   `SESSION_START.close_required` was `true`, render a `SESSION_CLOSE`
   packet (`scripts/gov_session_transfer.py close`) as the chat/session
   transport form of the close — it replaces a separate unstructured prose
   `SESSION CLOSE` narrative, not the durable repository updates (§2).
   Only after that does a `SESSION_START` packet for the *next* movement
   make sense; this protocol generates none automatically (§2).

## 6. Discoverability

`AI_START_HERE.md`'s "Governance and engineering law" section carries one
sentence pointing here and at `scripts/gov_session_transfer.py --help`. That
sentence is the entire integration: no second cold-start reading order, no
duplicate copy of §3/§4 in `AI_START_HERE.md` itself. Claude, Codex, Copilot
or another tool learns the protocol the same way it learns everything else
in this repository — by following the existing canonical entry point.

## 7. CLI

`scripts/gov_session_transfer.py` — dependency-free (stdlib only:
`argparse`, `json`, `dataclasses`, `pathlib`, `sys`). Five subcommands:

```
py scripts/gov_session_transfer.py start   --packet-id ... --movement ... --objective ... --base-sha SHA --branch NAME [--allow X]* [--deny X]* [--stop-on X]* [--authority-ref PATH]* [--session-mode SAME|NEW] [--close-required|--no-close-required] [--model-hint ...] [--out FILE]
py scripts/gov_session_transfer.py close   --packet-id ... --movement ... --outcome ... --validation ... --privacy-state ... --session-routing SAME|NEW --base-sha SHA --branch NAME --head-sha SHA [--pr-number N] [--merged|--no-merged] [--merge-sha SHA] [--changed X]* [--preserved X]* [--ref X]* [--risk X]* [--durable-state X]* [--next-movement ...] [--ui-effect ...] [--out FILE]
py scripts/gov_session_transfer.py validate  FILE|-  [--repo-root DIR]
py scripts/gov_session_transfer.py render    FILE|-              # bare JSON object -> sentinel-wrapped canonical text
py scripts/gov_session_transfer.py extract   FILE|-  [--packet-id ID]
```

Deterministic: `render`'s JSON is always `sort_keys=True`, 2-space indent,
`ensure_ascii=False`, one trailing newline — the same input always produces
byte-identical output. `--out` writes to an explicit path only (never
silently becomes tracked state); default output is stdout. Errors go to
`stderr`; payload/report JSON goes to `stdout`. Exit codes are stable and
documented in the module docstring: `0` success, `1` payload/content
invalid, `2` envelope/boundary malformed, `3` CLI usage error, `4` requested
`--packet-id` not found in a transcript. No network, no LLM call, no daemon,
no scheduler, no credential or device access — see
`tests/test_gov_session_transfer.py` for the exact contract each subcommand
enforces, including duplicate-key JSON, unsupported `protocol_version`,
whitespace-altered/odd/empty sentinel boundaries, sentinel-in-payload,
multi-packet transcript extraction with `--packet-id` selection,
deterministic-rendering/exit-code proof, the `git` object's required-field
and `merged`/`merge_sha` consistency rules, the exact-boundary and
one-byte-over cases of each message type's own payload ceiling, and the
CLI/schema parity assertions (§4).
