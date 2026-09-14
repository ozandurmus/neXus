# Antigravity as a third provider — measurement record 2 (the installed SDK)

## Status

**DRAFT — MEASUREMENT RECORD. NOT AUTHORITY, NOT AN ADAPTER.** Successor
amendment to `docs/design/ANTIGRAVITY_PROVIDER_MEASUREMENT_2026_09_14.md`
(2026-09-14), which recorded
the package from outside without installing it and listed five things as
unmeasured. The Product Owner authorized installing the SDK; this record is
step 2 of that document's §3. It resolves every open item **at the shape
level** and states precisely what only a live turn can still establish. It
authorizes nothing and binds no field.

Measured on the Product Owner's machine, 2026-09-14, in a throwaway
virtualenv outside the repository. Nothing was installed into the project
environment and no credential was used.

## 1. What was run

- `python3 -m venv` in a scratch directory; `pip install google-antigravity`
  resolved `google-antigravity 0.1.16` and pulled 44 packages, among them
  `google-genai 2.23.0`, `google-auth`, `mcp 2.2.0`, `protobuf 7.36.1`,
  `pydantic 2.13.5`, `cryptography`, `uvicorn`, `starlette`, `websockets`.
- Introspection only: `dir`, `inspect.signature`, `pkgutil.walk_packages`,
  pydantic `model_fields`, and `grep` over the installed tree. No agent was
  constructed, no network call was made, no model was invoked.

## 2. Corrections to measurement record 1

- **MC-1. The import path is `google.antigravity`, not `antigravity`.** The
  distribution is `google-antigravity`; its `top_level.txt` is `google`. A
  bare `import antigravity` resolves to **CPython's standard-library easter
  egg**, which opens `https://xkcd.com/353/` in a browser on import. This
  was observed directly during this measurement. Any adapter, test or probe
  that imports the short name silently imports the wrong module and performs
  an outbound browser launch. Record 1 listed the symbols correctly but not
  the path, and the path is the trap.
- **MC-2. There is no CLI, but there is a binary.** Record 1's finding
  stands — the wheel declares no console script and `/Applications/
  Antigravity.app` exposes no `bin`. The wheel does ship
  `google/antigravity/bin/localharness`, a **116.8 MB Mach-O 64-bit arm64
  executable** (the package is 115 MB of the 117 MB install). The SDK is a
  Python client over this local harness process; `ANTIGRAVITY_HARNESS_PATH`
  overrides its location. The wheel is therefore **platform-specific**
  (`macosx_11_0_arm64`); a Linux CI host needs its own wheel, and the
  repository would gain a 115 MB binary dependency it does not have today.
- **MC-3. `session_id` is absent — confirmed, and the replacement is
  named.** Record 1 was right that a resume keyed on `session_id` cannot
  work. See RS-1 below for what does exist.

## 3. The surface, as installed

### 3.1 Entry point

```
Agent(config: AgentConfig)
Agent.chat(prompt: str | Image | Document | Audio | Video | SlashCommand
           | Sequence[...]) -> ChatResponse          # async
Agent members: chat, conversation, conversation_id, is_started
ChatResponse(chunk_stream: AsyncIterator[StreamChunk | ToolCall | ToolResult],
             conversation: Any)
```

`LocalAgentConfig` is the concrete config for a locally-run agent and adds,
over `AgentConfig`: `model`, `models`, `api_key`, `vertex`, `project`,
`location`, `env`.

### 3.2 Effort — `ThinkingLevel`

Values: `minimal`, `low`, `medium`, `high`, `extra_high`. This is the
candidate `EFFORT_ALLOWLISTS["antigravity"]` (GOV.ORCH.2 §2.4). Note it is
a **superset** of the Codex list and disjoint from Claude's (`max`).

### 3.3 Budget — `BudgetConfig`, and it is not money

```
BudgetConfig(max_model_calls, max_tool_calls,
             max_input_tokens, max_output_tokens, max_total_tokens)
```

All are integer ceilings, all optional. **There is no USD ceiling.** The
orchestrator's `--max-budget-usd` has no direct equivalent; a token or call
ceiling would have to be derived, and a derivation is a contract decision,
not an adapter detail.

### 3.4 Budget exhaustion — a real signal, unlike Codex

`StopReason` values: `UNSPECIFIED`, `MAX_MODEL_CALLS_EXCEEDED`,
`MAX_TOOL_CALLS_EXCEEDED`, `MAX_INPUT_TOKENS_EXCEEDED`,
`MAX_OUTPUT_TOKENS_EXCEEDED`, `MAX_TOTAL_TOKENS_EXCEEDED`,
`QUOTA_EXHAUSTED`.

This is a genuine, vendor-emitted exhaustion vocabulary, so
`budget_exhausted()` for this provider can be evidence-based rather than
`None` (contrast `CodexAdapter.budget_exhausted`, which returns `None`
precisely because Codex proves nothing). Whether the ceiling that was hit is
the *orchestrator's* ceiling is a mapping question for the contract.

### 3.5 Usage — `UsageMetadata`, and there is no cost field

```
UsageMetadata(prompt_token_count, cached_content_token_count,
              candidates_token_count, thoughts_token_count,
              total_token_count, service_tier)
```

Candidate mapping to GOV.ORCH.4 §3.1's normalized shape, **proposed, not
decided**: `prompt_token_count` → `input_tokens`;
`cached_content_token_count` → `cache_read_input_tokens`;
`candidates_token_count` → `output_tokens`;
`cache_creation_input_tokens` → 0 (no cache-write concept is exposed).
`thoughts_token_count` has **no home in the current normalized shape** and
is the one field that needs a contract decision rather than a mapping.

There is **no cost field anywhere in the SDK**. Cost for this provider is
therefore an estimate from the requested model, marked with a trailing
asterisk and totalled as a comparable, exactly as GOV.ORCH.4-A CU-4 already
requires for Codex — not billed spend.

`Conversation` additionally exposes `total_usage`, `last_turn_usage`,
`trajectory_usages` and `turn_count`, so per-turn and cumulative usage are
both readable without accumulating them in the adapter.

### 3.6 Event stream — `Step`

`Conversation.receive_steps()` / `receive_chunks()` yield `Step` objects:

```
Step(id, step_index, trajectory_id, parent_trajectory_id, depth,
     type, source, target, status, content, content_delta,
     thinking, thinking_delta, tool_calls, error,
     is_complete_response, structured_output, usage_metadata, **extra_data)
```

- `StepType`: `TEXT_RESPONSE`, `TOOL_CALL`, `SYSTEM_MESSAGE`, `COMPACTION`,
  `FINISH`, `THINKING`, `UNKNOWN`
- `StepStatus`: `ACTIVE`, `DONE`, `WAITING_FOR_USER`, `ERROR`, `CANCELED`,
  `UNKNOWN`
- `StepSource`: `SYSTEM`, `USER`, `MODEL`, `UNKNOWN`
- `AgentBehavior`: `autonomous`, `interactive`, `minimal`

`usage_metadata` rides on the step, so the usage reader has a per-step hook.
`WAITING_FOR_USER` is the interesting status for GOV.ORCH.4's stuck
detector: it is a legitimate idle, not a hang, and must not be treated as
one. `AgentBehavior.autonomous` is the default and is the mode a dispatched
worker would run in.

### 3.7 Resume — `conversation_id`, not `session_id` (RS-1)

`AgentConfig`/`LocalAgentConfig` carry `conversation_id`,
`session_continuation_mode` and `save_dir`. `SessionContinuationMode` values
are `resume`, `create_or_resume`, `create_only`. `Agent.conversation_id` and
`Conversation.conversation_id` read it back. `Conversation` also exposes
`cancel`, `clear_history`, `history`, `is_idle`, `wait_for_idle`,
`disconnect`.

So GOV.ORCH.12 RQ-6 ("the provider's own resume path") and GOV.ORCH.10's
budget resume both have a real mechanism here: persist `conversation_id`,
re-open with `session_continuation_mode="resume"` and the same `save_dir`.

### 3.8 Credentials and outbound path

`LocalAgentConfig.api_key`, or Vertex via `vertex`, `project`, `location`.
Environment names referenced in the installed source: `GEMINI_API_KEY` (10
occurrences), `GOOGLE_CLOUD_PROJECT` (5), `ANTIGRAVITY_HARNESS_PATH` (5),
`ANTIGRAVITY_ALLOW_CPU` (2). **None of these is set on this machine**, and
no Google credential exists in this environment.

`models.DEFAULT_MODEL` is `gemini-3.8-flash`.

## 4. The architectural finding — this is the contract's real question

`ProviderAdapter` (`scripts/orchestrator_providers.py`, GOV.ORCH.2 §2.1) is
defined entirely around **a subprocess and a JSON-lines log file**:
`build_argv`, `build_env`, `stdin_source`, `summarize_line`,
`session_id_from_log`, `observed_model`, `usage_from_event`,
`budget_exhausted`. Both shipping adapters return argv for a binary the
orchestrator spawns, and every reader parses lines that binary wrote.

**Antigravity has no argv.** It is an in-process async Python API over a
bundled harness process it starts itself. There is nothing for
`build_argv` to return and nothing writing the log the four readers read.

Two shapes answer this. Both are stated; neither is chosen here.

- **Option A — a repository-owned shim.** `scripts/antigravity_worker.py`
  is spawned by the orchestrator like any other binary, drives the SDK, and
  writes one JSON line per `Step` in a shape the adapter's readers parse.
  `ProviderAdapter` is unchanged; process lifetime, timeout, heartbeat,
  kill, worktree `cwd` and the "task content never enters argv" rule all
  keep working as they do today. The shim is new code the repository owns
  and must test.
- **Option B — widen the protocol** to admit an in-process provider. No
  shim, but it reaches `_spawn_engineer`, the stuck detector, `verify`, the
  usage reader and every existing adapter test — a much larger blast radius
  for a provider that has not yet run once.

A third consideration belongs to whoever writes the contract: Antigravity's
own harness runs tools itself (`BuiltinTools`, `run_command_config`,
`mcp_servers`, `policies`, `hooks`). The repository's worker discipline —
`roles/WORKER.md`, the PreToolUse gate, the pre-push hook — is expressed for
Claude and Codex. A provider with **no pre-push git gate must be added to
`FORCED_MERGE_MODE`**, as Codex is, or it can reach `gh pr merge` itself.

## 5. What a live turn is still needed for

Shapes above are read from the installed source and are facts about the
distribution. These are not, and no static reading can settle them:

1. Whether `usage_metadata` is actually **populated** on steps, and on
   which step types — a field existing is not a field carrying a value
   (`AGENTS.md`, evidence laws).
2. The real **cadence** of `Step` emission, which GOV.ORCH.4's stuck
   detector is calibrated against.
3. Whether `localharness` starts **headless**, without the desktop
   application running, and what it opens outbound when it does.
4. Which `StopReason` a `BudgetConfig` ceiling actually produces.
5. Whether a `resume` genuinely restores a prior conversation across
   processes from `save_dir`.

Each needs one trivial agent turn, which needs a `GEMINI_API_KEY` or Vertex
credentials. **That is a Product Owner decision:** a new credential and a
new outbound path, neither of which this repository has authorized.

## 6. Cross-references

- `ANTIGRAVITY_PROVIDER_MEASUREMENT_2026_09_14.md` — record 1, corrected by §2.
- `GOV_ORCH_2_PROVIDER_ADAPTER.md` (FROZEN) §2.1, §2.3, §2.4, §2.5.
- `GOV_ORCH_4_...` §3.1, §3.2; `GOV_ORCH_4A_...` CU-4 — usage and cost honesty.
- `GOV_ORCH_10_...`, `GOV_ORCH_12_...` RQ-6 — the resume paths §3.7 would serve.
- `roles/PO.md` §2 — the measurement rule this record continues to satisfy.
