# GOV.PO.1 — local file-based relay protocol

## Status

**DRAFT — not ratified.** Implemented under `GOV_PO_1_LOCAL_RELAY_PROTOCOL`
(relay `ozandurmus/nexus-agent-relay#15`), filed directly from an
interactive `nexus-po` session at the Product Owner's explicit request.
Promoting this mechanism beyond DRAFT — e.g. making it the required
default over the GitHub-based relay — is an explicit future `DECIDE`
question, not decided here (see "Non-supersession" below). A
`nexus-decision-council` `REVIEW` round is recommended once relay#14's own
gate fix has proven itself, before this mechanism is used for anything
beyond the small, tightly-coupled gate-fix-scale movements it was built
for.

## 1. Purpose

`docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md` ("GOV.RELAY.1") exists because
two agent sessions frequently cannot read one another directly, and its
GitHub-issue transport is the right tool when that is genuinely true:
cross-machine work, a cloud-hosted agent, or anything a human outside the
current machine needs to see. For a run of small, tightly-coupled
same-machine movements — a Product Owner session and an engineer session
both running against the same checkout — routing every intermediate
exchange through a GitHub issue, `gh` authorization and network round-trips
is overhead the two sessions do not need: they can already read each
other's output through the one thing they truly share, the filesystem.

This document defines an **additional** transport for exactly that case:
one JSON file per movement, tracked in git, exchanged by direct read/write
against the shared checkout instead of `gh issue`/`gh pr` calls. It reuses
`GOV.RELAY.1`'s marker vocabulary and `GOV.SESSION.1`'s
`SESSION_START`/`SESSION_CLOSE` report schema **verbatim** — this is a new
transport for an unchanged vocabulary, not a new vocabulary.

## 2. Non-supersession

This protocol does **not** amend, deprecate, or replace
`NEXUS_AGENT_RELAY_PROTOCOL.md`, `.github/prompts/relay-bootstrap.prompt.md`,
or the `RELAY_READY owner/repository#issue` workflow. Those stay exactly as
they are, for exactly the cases they already serve: cross-machine
coordination, a cloud-hosted engineer or PO session, or any exchange a
human outside this machine needs to see without being handed a local file.
A session receiving `RELAY_READY owner/repository#issue` follows the
GitHub protocol; a session receiving a local relay file path (or told to
check `relay/`) follows this one. Neither locator implies the other.

Retroactively migrating relay `#11`–`#14`'s existing GitHub history into
this format is explicitly out of scope. This slice never modifies
`NEXUS_AGENT_RELAY_PROTOCOL.md` itself.

## 3. File shape

One tracked JSON file per movement under `relay/`, named
`relay/NXS-LOCAL-<4-digit-id>-<slug>.json` — e.g.
`relay/NXS-LOCAL-0001-example-movement.json`. `relay/` is a normal,
git-tracked directory (not gitignored, not a scratch/temp pattern): these
files are durable governance history, the same category as
`project/*.json`, never ephemeral packet staging.

```json
{
  "schema_version": 1,
  "id": "NXS-LOCAL-0001",
  "movement": "EXAMPLE_MOVEMENT",
  "refs": ["docs/design/SOME_CONTRACT.md"],
  "status": "AWAITING_ENGINEER",
  "next_actor": "engineer",
  "created_at": "2026-09-08T09:00:00Z",
  "updated_at": "2026-09-08T09:00:00Z",
  "entries": [
    { "seq": 1, "marker": "SESSION_START", "actor": "po",
      "timestamp": "2026-09-08T09:00:00Z", "report": { "...": "..." } }
  ]
}
```

- `id` — the assigned sequential id, matching the filename. Assigned by the
  tool (`create`), never chosen by the caller: it scans `relay/` for the
  highest existing `NXS-LOCAL-NNNN` id and takes the next one.
- `movement` — a short, non-empty movement identifier, the same role
  `NEXUS_AGENT_RELAY_PROTOCOL.md`'s `movement` field plays.
- `refs` — repository-relative paths to authoritative documents for this
  movement (may be empty; existence is not verified, exactly like
  `GOV.SESSION.1`'s own `refs`).
- `status` — one of `OPEN`, `IN_PROGRESS`, `AWAITING_PO`,
  `AWAITING_ENGINEER`, `CLOSED` (§5).
- `next_actor` — `"po"`, `"engineer"`, or `null` (only when `status` is
  `CLOSED`). This is the turn-ownership field (§6) — the single-writer
  mechanism the Product Owner asked for.
- `entries` — append-only. Never rewritten or reordered; a correction is
  itself a new `RELAY_CORRECTION` entry (§4), exactly as
  `NEXUS_AGENT_RELAY_PROTOCOL.md` §6 already requires for the GitHub
  transport.

## 4. Entry shape and marker vocabulary

Every entry carries `seq` (1-based, strictly increasing, matching array
position), `marker`, `actor` (`"po"` or `"engineer"` — who wrote this
entry), and `timestamp` (UTC, `YYYY-MM-DDTHH:MM:SSZ`). The marker vocabulary
is **exactly** `NEXUS_AGENT_RELAY_PROTOCOL.md`'s five markers plus the two
`GOV.SESSION.1` packet types — seven total, closed:

| Marker | Extra required fields | Meaning (unchanged from the GitHub protocol) |
| --- | --- | --- |
| `SESSION_START` | `report` (`GOV.SESSION.1` `SESSION_START` schema, verbatim) | The movement's opening packet. Only legal as `entries[0]`; written by `create`, never by `append`. |
| `SESSION_CLOSE` | `report` (`GOV.SESSION.1` `SESSION_CLOSE` schema, verbatim), `outcome` | The movement's final packet. Only legal as the last entry; sets `next_actor` to `null` and `status` to `CLOSED` unconditionally. |
| `RELAY_ACK` | `subject`, `text` | Receipt/structural-validation result; never approval. |
| `RELAY_NOTE` | `subject`, `text` | Factual status or evidence; never a decision. |
| `RELAY_QUESTION` | `subject`, `text` | One material question blocking dependent work until answered. |
| `RELAY_DECISION` | `subject`, `text`, `authorized_by`, `scope`, `supersedes` | Authoritative Product Owner direction only — the same three fields `NEXUS_AGENT_RELAY_PROTOCOL.md` §1 requires for an agent-published decision, required and validated here as real structured fields rather than free-text convention. |
| `RELAY_CORRECTION` | `subject`, `text` | Identifies malformed, stale, or incorrect prior material and its replacement; never deletes the entry it corrects. |

`subject` is a short non-empty string (the marker's "first line" in the
GitHub protocol's grammar); `text` is the concise English body. Every entry
may additionally carry `good_to_go: true|false` (§7).

Structural rules `validate` enforces, mirroring `NEXUS_AGENT_RELAY_PROTOCOL.md`
§3's canonical GitHub shape:

- `entries[0].marker` must be `SESSION_START`.
- At most one `SESSION_START`, and only at position 0.
- At most one `SESSION_CLOSE`, and only as the last entry.
- Every other entry's marker is one of the five relay markers.
- `RELAY_DECISION` always carries `authorized_by`/`scope`/`supersedes`,
  matching `NEXUS_AGENT_RELAY_PROTOCOL.md` §1's requirement for an
  agent-published decision to trace to a dated written Product Owner
  instruction — the same discipline, now a validated field instead of
  parsed prose.
- `next_actor`/`status` are internally consistent with the last entry
  (§5) — a hand-edited file that violates this fails `validate`.

## 5. Status vocabulary

`status` is derived and persisted by the tool on every `create`/`append`,
never hand-set independently of `next_actor`:

- `OPEN` — exactly one entry (`SESSION_START`), nobody has responded yet.
- `AWAITING_PO` — `next_actor == "po"`.
- `AWAITING_ENGINEER` — `next_actor == "engineer"` and more than one entry
  exists (the PO has handed off at least once).
- `IN_PROGRESS` — set only via an explicit `append --in-progress` flag: the
  actor who now holds the turn (`next_actor` after this append) is actively
  working, not idly awaiting the other side's reply. Orthogonal to
  `next_actor` — it does not change who may write next, only how a human
  or session skimming `status` should read the current wait.
- `CLOSED` — `next_actor` is `null` (a `SESSION_CLOSE` or a closing entry;
  see §6).

## 6. Turn ownership

`next_actor` is enforced by the tool itself (`local_relay.py append`), not
merely documented convention: an `append --role <r>` call is rejected with
a non-zero exit when `<r>` does not equal the file's current `next_actor`.
This is the actual point of the mechanism — removing ambiguity about whose
turn it is without a human re-explaining state in chat.

- `create` always sets `next_actor` to the role the creator is *not* — in
  the normal flow, the Product Owner creates and `next_actor` becomes
  `"engineer"`.
- Every `append` other than `SESSION_CLOSE` must state `--next {po|engineer}`
  explicitly, which becomes the new `next_actor`. This is a deliberate
  design choice over "every append auto-flips the turn": a real exchange
  is not strict ping-pong (an engineer's `RELAY_ACK` on receiving a
  `SESSION_START` does not hand the turn back to the PO — the engineer
  keeps it and starts implementing), so the appender states who goes next
  rather than the tool guessing from the marker alone.
- `--close` may be passed with any marker except `SESSION_START` (there is
  nothing to close yet) to set `next_actor` to `null` and `status` to
  `CLOSED` regardless of `--next` — generalizing the packet's "`SESSION_CLOSE`
  or closing `RELAY_DECISION`" phrasing to any marker, for parity with the
  GitHub transport's own `GOV_PO_1_GATE_4` precedent (`gh issue close`
  accepts an inline comment carrying any of the five markers, not only
  `RELAY_DECISION`).
- `SESSION_CLOSE` always closes unconditionally (`--next`/`--close` are
  rejected as redundant with it).

**Concurrency.** Two processes editing the same file without a real lock
could race if a human runs both a PO and an engineer session genuinely
concurrently rather than in the described turn-taking sequence. `append`
re-reads the file's exact bytes immediately before writing and compares
them, by hash, to what it read at the start of the call; a mismatch (the
file changed underneath it — a legitimate concurrent write, not merely the
turn-ownership check above) fails closed with a clear message instead of
silently overwriting the newer state. This is optimistic concurrency
control, not a durable lock (`utils/control_plane_store.py`'s SQLite
advisory locking is a different, heavier mechanism for a different
problem — a local JSON file exchanged by two interactive sessions does not
need it); it is sufficient for the turn-taking usage this protocol
describes, and insufficient for anything claiming true concurrent writers.

## 7. The `good_to_go` fast path

Routine mutual approvals ("looks right, go ahead" / "confirmed, proceeding")
do not need a full narrative entry. Any `append` may pass `--good-to-go`;
when combined with a `RELAY_ACK`, `RELAY_NOTE`, `RELAY_QUESTION`, or
`RELAY_CORRECTION` marker, `--text` becomes optional (defaulting to `"good
to go"`, and `--subject` to the marker's own name) so the entire round-trip
can be one flag. `good_to_go` is stored as `true` on the entry — this is a
convenience marker for humans/sessions skimming the file, not a distinct
eighth marker and not itself an approval: a `RELAY_DECISION` still needs
its own `authorized_by`/`scope`/`supersedes`, `good_to_go` or not.

## 8. Notification model — honest, not automatic

There is no background watcher, daemon, or polling loop, and this slice
does not add one (explicitly out of scope). A Claude Code session is not an
always-running process: "the next actor gets notified" means exactly what
it already means for the GitHub transport — **the next invoked session
reads the relevant `relay/<file>.json`'s current state as the first step of
its own episode start**, the same discipline `AI_START_HERE.md` already
requires before treating any packet's claims as true. A human still starts
each session and points it at the right file (or the PO/engineer skill
prompts do, exactly as `RELAY_READY owner/repo#issue` is a human- or
skill-supplied locator today, not a push notification either).

## 9. CLI — `scripts/local_relay.py`

Stdlib only, offline, synchronous — no network, daemon, credential, or
device access, mirroring `scripts/gov_session_transfer.py`'s own
constraints exactly. Imports `scripts/gov_session_transfer.py` directly to
validate `SESSION_START`/`SESSION_CLOSE` `report` objects against its
existing schema (`_REPORT_SCHEMA_BY_TYPE`, `_validate_node`, `OUTCOMES`) —
reused, not re-derived, so the two transports can never drift into two
different ideas of what a valid report looks like.

```
py scripts/local_relay.py create   --start FILE|- [--dir relay] [--slug SLUG]
py scripts/local_relay.py append   --file FILE --role {po|engineer} --marker MARKER
                                    [--subject S] [--text T]
                                    [--report FILE|-] [--outcome OUTCOME]
                                    [--authorized-by A] [--scope S] [--supersedes S]
                                    [--next {po|engineer}] [--close] [--good-to-go]
                                    [--in-progress]
py scripts/local_relay.py status   --file FILE
py scripts/local_relay.py validate --file FILE
```

- `create` reads one **bare** `SESSION_START` packet object — the exact
  same shape `gov_session_transfer.py render` expects
  (`protocol_version: 2, message_type: "SESSION_START", movement, refs,
  report`) — from `FILE` or stdin (`-`), validates it with
  `gov_session_transfer.validate_fields` unchanged, and only on success
  assigns the next sequential id, derives a filesystem-safe slug from
  `movement` (or uses `--slug` if given), and writes
  `relay/NXS-LOCAL-<id>-<slug>.json`. Nothing is written on a validation
  failure, exactly like `gov_session_transfer.py render`. Prints the
  created path.
- `append` re-checks `next_actor` against `--role` (§6), builds the new
  entry per the marker's required-field table (§4), re-verifies the file's
  content hash immediately before writing (§6's concurrency note), and
  writes atomically (temp file + `os.replace`, the same discipline
  `utils/device_registry.py` already uses for its own document writes).
  Exit `1` on a rejected wrong-turn append, a concurrent-write conflict, or
  a schema violation; exit `2` on a usage error (bad arguments, missing
  file); exit `0` on success.
- `status` is read-only: prints `{id, movement, status, next_actor,
  entry_count, last_marker, last_actor, last_timestamp}` as one JSON line.
- `validate` prints `{"valid": bool, "errors": [...]}`, exit `0`/`1`, the
  same contract as `gov_session_transfer.py validate`.

## 10. Security and privacy

`relay/*.json` content is repository-facing engineering metadata, exactly
like the GitHub transport's issue/comment bodies
(`NEXUS_AGENT_RELAY_PROTOCOL.md` §7): no credential, token, raw device
output, local identity value, management address, or other sensitive
operational value belongs in it, and the repository privacy gate
(`--repository-privacy-check`) scans it like any other tracked file. A
local relay file's existence, validity, or `good_to_go` flag never
authorizes device contact, credential use, a Git operation, or a new
network path by itself — exactly the same non-authorizing posture
`NEXUS_AGENT_RELAY_PROTOCOL.md` §2 gives the GitHub locator.

`create`/`append`'s `--start`/`--report` flags may reference an arbitrary
local file path without a scratch-pattern restriction (unlike the GitHub
transport's `--body-file`, which is pinned to the `nexus_po_*` scratch
pattern). This is a deliberate difference, not an oversight: `--body-file`
is restricted because it posts to an externally-visible GitHub comment — a
real exfiltration surface. `relay/*.json` is a file the interactive PO role
already has direct `Edit`/`Write` access to under this same movement
(`scripts/nexus_po_tool_gate.py`'s new `relay/*.json` pattern); a
`--start`/`--report` path restriction here would restrict nothing an
existing, already-granted `Edit` call could not already accomplish by hand.

## 11. Acceptance criteria

- `relay/` is tracked, one JSON file per movement, id assigned by the tool.
- `entries` reuses `NEXUS_AGENT_RELAY_PROTOCOL.md`'s five markers plus
  `GOV.SESSION.1`'s two packet types verbatim; `SESSION_START`/`SESSION_CLOSE`
  `report` objects validate against the unchanged `GOV.SESSION.1` schema.
- `RELAY_DECISION` requires `authorized_by`/`scope`/`supersedes` as real,
  validated fields.
- `next_actor` is enforced by the tool: a wrong-role `append` is rejected,
  never silently applied out of order.
- The `good_to_go` fast path exists and does not weaken `RELAY_DECISION`'s
  own requirements.
- This document does not amend `NEXUS_AGENT_RELAY_PROTOCOL.md`,
  `.github/prompts/relay-bootstrap.prompt.md`, or the GitHub `RELAY_READY`
  workflow, and says so explicitly.
- No background watcher/daemon exists or is implied.
- No product/device/UI behavior changes as part of this movement.
