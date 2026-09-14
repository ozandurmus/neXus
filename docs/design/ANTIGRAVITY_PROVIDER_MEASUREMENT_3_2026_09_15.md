# Antigravity as a third participant — measurement record 3 (the CLI that exists)

## Status

**DRAFT — MEASUREMENT RECORD. NOT AUTHORITY, NOT AN ADAPTER.** Correcting
amendment to `docs/design/ANTIGRAVITY_PROVIDER_MEASUREMENT_2_2026_09_14.md`
(2026-09-14), whose central factual claim was wrong. Records what was measured
on the Product Owner's machine on 2026-09-15. It authorizes nothing and binds
no field.

**This corrects a Product Owner assistant defect.** Records 1 and 2 both state
that no command-line interface exists. Both searched `PATH` and the
application bundle and neither searched `~/.gemini/`. A command-line interface
does exist, has existed since the application was installed, and is what the
Product Owner's own tooling pointed at. The wrong claim was the basis for
proposing an SDK path that requires a credential the command-line path does
not need.

## 1. What exists

- `/Applications/Antigravity.app/Contents/Resources/bin/language_server` —
  a 144 MB executable, installed 2026-09-11 with the application. This is the
  real entry point.
- `/Users/OzanDur/.gemini/antigravity/bin/agentapi` — a 107-byte shell
  wrapper: `exec "<app>/Contents/Resources/bin/language_server" agentapi "$@"`.
  Note it points at `/Volumes/Antigravity/...`, the mounted installer image,
  not at the installed application. Anything driving it should call the
  installed binary directly rather than trust that path.

## 2. The command surface, as the tool reports it

```
Usage: agentapi <command> [args]

Available Commands:
  get-conversation-metadata <conversation_id>
  new-conversation [--model=<flash_lite|flash|pro>] [--title=<title>] [--profile=<profile>] <prompt>
  send-message [--title=<title>] <recipient_id> <content>
```

Observations, not inferences:

- Model selection is `flash_lite`, `flash` or `pro`. **There is no effort or
  thinking-level flag** on this surface.
- **There is no working-directory flag.** Anything driving it must control the
  working directory of the process itself.
- **There is no JSON-output flag and no resume verb.** Continuation is
  `send-message` into an existing conversation, so a resume is keyed on the
  conversation id.
- **There is no budget flag of any kind.**

## 3. The event stream

Each conversation writes JSON Lines to
`~/.gemini/antigravity/brain/<conversation_id>/.system_generated/logs/transcript.jsonl`.

Measured over one existing conversation of 86 lines, by counting field names
and enumerated values only — no content was read:

| | |
|---|---|
| Fields present | `step_index`, `source`, `type`, `status`, `created_at`, `content`, `tool_calls`, `thinking`, `truncated_fields` |
| `type` values seen | `PLANNER_RESPONSE` (43), `GENERIC` (39), `USER_INPUT` (4) |
| `status` values seen | `DONE` (86) |
| `source` values seen | `MODEL` (82), `USER_EXPLICIT` (4) |
| Invalid lines | 0 |

This is the same `Step` vocabulary the Python SDK exposes, so the two surfaces
agree on the shape.

**No usage, token or cost field appears anywhere in the stream.** Zero lines
of 86 carry one. A dispatch driven this way produces no token count, no cost
figure and no turn count, and its `GOV.ORCH.13` ledger row would be `unknown`
in every derived column. The Product Owner has accepted that for the trial:
being able to follow the work is enough, and cost accounting is not required
from this participant.

## 4. Why it cannot be driven from an ordinary shell today

Invoked from a normal terminal the tool exits with:

```
{ "error": "ANTIGRAVITY_LS_ADDRESS is not set" }
```

`agentapi` is a client of the running desktop session's language server, not a
standalone agent. The desktop application sets that variable for terminals it
spawns. The server is running (`--standalone --subclient_type hub`) but its
port is allocated dynamically (`--https_server_port 0`) and it is protected by
a per-launch CSRF token.

Discovering that address from outside would mean binding an undocumented
internal interface, which `AGENTS.md`'s vendor-semantics and diagnostic-path
laws both weigh against. **It is a Product Owner decision, not a mechanical
detail, and it is not taken here.**

## 5. What this leaves

- **Available today, with no credential and no code:** the hybrid relay path.
  The Product Owner assistant prepares the movement, its worktree and its
  `.nexus/WORKER.md`; the participant is given the task in its own interface;
  it closes the relay with its `SESSION_CLOSE`; the assistant verifies,
  reviews and merges. Nothing in the orchestrator changes.
- **Available with a decision:** an adapter, once the Product Owner accepts
  binding the address-discovery interface. The command surface, the JSON Lines
  stream and the conversation-id resume in sections 2 and 3 are what such an
  adapter would bind, and they fit `ProviderAdapter` far better than the SDK
  does — `build_argv` has a real argv to return and the readers have a real
  log to read. The shim question of record 2 section 4 does not arise on this
  path.
- **Superseded:** record 2's section 4 Option A and Option B were framed for
  an in-process SDK with no argv. That framing was a consequence of the wrong
  premise corrected here.

## 6. Cross-references

- `ANTIGRAVITY_PROVIDER_MEASUREMENT_2026_09_14.md`, `..._2_2026_09_14.md` — corrected by this record.
- `GOV_ORCH_2_PROVIDER_ADAPTER.md` (FROZEN) — the protocol an adapter would bind.
- `GOV_ORCH_13_THE_DISPATCH_LEDGER.md` (FROZEN) — the columns section 3 says cannot be filled.
- `docs/reference/PROVIDER_OPERATING_NOTES.md` — the operating summary.
