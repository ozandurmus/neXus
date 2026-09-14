# Antigravity as a third provider — measurement record

## Status

**DRAFT — MEASUREMENT RECORD. NOT AUTHORITY, NOT AN ADAPTER.** Records what
was observed on the Product Owner's machine on 2026-09-14 about the surface a
future `AntigravityAdapter` would bind, so that the observation survives the
session that produced it (`AGENTS.md` item 7: chat is never authoritative)
and so `roles/PO.md` §2's measurement rule can be satisfied before any
adapter is written. It authorizes nothing and binds no field.

## 1. What was run, and what came back

- `which antigravity`, `which ag` — **no binary on PATH**. The application
  is installed as `/Applications/Antigravity.app`, whose bundle exposes only
  its own executable; a search of the bundle for a `bin` directory or a
  shell-command installer found nothing. **There is no CLI to drive today.**
- `python3 -m pip index versions google-antigravity` — the package exists on
  the index; versions 0.1.0 through **0.1.16**.
- `python3 -m pip download google-antigravity --no-deps` — resolved to
  `google_antigravity-0.1.16-py3-none-macosx_11_0_arm64.whl`. The package is
  **not installed** in any environment on this machine; the wheel was
  downloaded to a temporary directory and inspected without installing.
- The wheel's own metadata: `Name: google-antigravity`,
  `Version: 0.1.16`, `Summary: Google Antigravity SDK for building AI
  agents`. It contains 94 files, 88 of them Python modules, and declares
  **no console script**, which is consistent with the absence of a CLI.
- Symbols found by scanning those modules' source: `class Agent`,
  `class LocalAgentConfig`, `class CapabilitiesConfig`, `async def chat`,
  `def chat`, `tool_calls`, `thread_id`, `usage`. **`session_id` was not
  found** — a resume keyed on it, as an early sketch proposed, would not
  work.

## 2. What this does and does not establish

- **Established:** a published SDK exists, its name and version are fixed
  above, and the type and method names a sketch quoted are present in the
  distributed source rather than only in conversation.
- **Not established, and required before an adapter:** the signatures and
  semantics of those types (a name in source is not a contract); how a run
  reports tokens and cost, which `GOV.ORCH.4`'s usage reader needs; whether
  anything corresponds to a budget ceiling the orchestrator can enforce;
  what a resume looks like without `session_id`; what the event stream emits
  and at what cadence, which the stuck detector of `GOV.ORCH.4` depends on;
  and whether the SDK requires credentials or network access this repository
  has not authorized.
- **Not established:** that installing the SDK is acceptable. It is a new
  runtime dependency and a new outbound path; the Product Owner decides.

## 3. The sequence, if the Product Owner wants it

1. Product Owner authorizes installing `google-antigravity` in a throwaway
   environment (or names a CLI they intend to drive instead).
2. A local probe runs one trivial agent turn and records, in a successor to
   this document: the exact call made, the event shapes emitted, the token
   and cost fields if any, and the resume mechanism.
3. A short contract (`GOV.ORCH.14`) fixes the adapter's obligations against
   the `ProviderAdapter` protocol, `EFFORT_ALLOWLISTS`, `FORCED_MERGE_MODE`
   (a provider with no pre-push git gate is forced to orchestrator merge
   mode, as Codex is) and the rule that task content never enters argv.
4. One implementation movement writes the adapter and its tests.

Steps 1 to 3 cost no dispatch. Only step 4 does.

## 4. Cross-references

- `GOV_ORCH_2_PROVIDER_ADAPTER.md` (FROZEN) — the protocol an adapter binds.
- `GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md` and
  `GOV_ORCH_4A_...` — the usage and stuck-detection obligations §2 names.
- `roles/PO.md` §2 — the measurement rule this record exists to satisfy.
