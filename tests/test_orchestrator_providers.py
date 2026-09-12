"""GOV.ORCH.2 -- tests for scripts/orchestrator_providers.py (docs/design/
GOV_ORCH_2_PROVIDER_ADAPTER.md, sections 2.1-2.4).

`codex` was not installed when this suite was written (`which codex` /
`codex --help` both failed) -- CodexAdapter's argv/JSON-lines assumptions
are exercised only against fixtures this suite controls itself, per the
module's own docstring.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator_providers as op  # noqa: E402


# --- 2.4: per-provider effort allowlists -----------------------------------

@pytest.mark.parametrize("effort", ["low", "medium", "high", "max"])
def test_validate_effort_accepts_every_claude_allowlist_value(effort):
    op.validate_effort("claude", effort)  # must not raise


@pytest.mark.parametrize("effort", ["minimal", "low", "medium", "high"])
def test_validate_effort_accepts_every_codex_allowlist_value(effort):
    op.validate_effort("codex", effort)  # must not raise


def test_validate_effort_accepts_none_for_any_provider():
    op.validate_effort("claude", None)
    op.validate_effort("codex", None)


def test_validate_effort_rejects_max_for_codex():
    # "max" is a Claude-only value (section 2.4's own table) -- never
    # silently passed through for a provider that does not list it.
    with pytest.raises(op.InvalidEffortError):
        op.validate_effort("codex", "max")


def test_validate_effort_rejects_minimal_for_claude():
    with pytest.raises(op.InvalidEffortError):
        op.validate_effort("claude", "minimal")


def test_validate_effort_rejects_an_unknown_value():
    with pytest.raises(op.InvalidEffortError):
        op.validate_effort("claude", "ultra")


# --- 2.5: merge-mode resolution ---------------------------------------------

def test_resolve_merge_mode_defaults_to_engineer_for_claude():
    assert op.resolve_merge_mode("claude", None) == "engineer"


def test_resolve_merge_mode_honors_an_explicit_choice_for_claude():
    assert op.resolve_merge_mode("claude", "orchestrator") == "orchestrator"


def test_resolve_merge_mode_forces_orchestrator_for_codex_even_if_engineer_requested():
    assert op.resolve_merge_mode("codex", "engineer") == "orchestrator"
    assert op.resolve_merge_mode("codex", None) == "orchestrator"


# --- 2.2: Claude adapter argv -----------------------------------------------

def test_claude_adapter_build_argv_matches_the_pre_gov_orch_2_shape(tmp_path):
    prompt_path = tmp_path / "engineer_prompt.txt"
    prompt_path.write_text("do the movement", encoding="utf-8")
    adapter = op.ClaudeAdapter(profile_path=tmp_path / "profile.json")
    argv = adapter.build_argv(
        prompt_path=prompt_path, worktree=tmp_path / "wt", model="opus", effort="high",
        budget_usd=3.0, extra_dirs=[tmp_path / "relay"], resume_session_id=None,
    )
    assert argv[:3] == ["claude", "-p", "do the movement"]
    assert argv[argv.index("--settings") + 1] == str(tmp_path / "profile.json")
    assert argv[argv.index("--add-dir") + 1] == str(tmp_path / "relay")
    assert "--output-format" in argv and argv[argv.index("--output-format") + 1] == "stream-json"
    assert "--verbose" in argv
    assert argv[argv.index("--model") + 1] == "opus"
    assert argv[argv.index("--effort") + 1] == "high"
    assert "--resume" not in argv
    # Task content -- as opposed to the fixed, generic ENGINEER_PROMPT --
    # never enters argv for either adapter; this adapter only ever embeds
    # whatever text was written to prompt_path, which the caller (never
    # this adapter) controls.
    assert "approved_task.json" not in " ".join(argv)


def test_claude_adapter_stdin_source_is_none():
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.stdin_source(Path("/dev/null")) is None


# --- AC-2/AC-3: Codex adapter argv -------------------------------------------

def test_codex_adapter_fresh_dispatch_argv_matches_the_contract(tmp_path):
    prompt_path = tmp_path / "engineer_prompt.txt"
    prompt_path.write_text("do the movement -- approved task content never goes here", encoding="utf-8")
    adapter = op.CodexAdapter()
    worktree = tmp_path / "wt"
    relay_dir = tmp_path / "canonical" / "relay"
    argv = adapter.build_argv(
        prompt_path=prompt_path, worktree=worktree, model="gpt-5-codex", effort="medium",
        budget_usd=3.0, extra_dirs=[relay_dir], resume_session_id=None,
    )
    assert argv == [
        "codex", "exec", "--cd", str(worktree), "--json",
        "-m", "gpt-5-codex", "-c", "model_reasoning_effort=medium",
        "-c", "sandbox_workspace_write.network_access=true",
        "--sandbox", "workspace-write",
        "--add-dir", str(relay_dir),
        "-o", str(worktree / ".nexus" / "engineer_last_message.txt"),
        "-",
    ]
    # AC-2: the task/prompt content is delivered on stdin, never as an
    # argv element.
    assert "do the movement" not in " ".join(argv)


def test_codex_adapter_fresh_dispatch_argv_without_model_or_effort(tmp_path):
    prompt_path = tmp_path / "engineer_prompt.txt"
    prompt_path.write_text("x", encoding="utf-8")
    adapter = op.CodexAdapter()
    worktree = tmp_path / "wt"
    argv = adapter.build_argv(
        prompt_path=prompt_path, worktree=worktree, model=None, effort=None,
        budget_usd=None, extra_dirs=[], resume_session_id=None,
    )
    assert "-m" not in argv
    assert "model_reasoning_effort" not in " ".join(argv)
    assert argv[-1] == "-"


def test_codex_adapter_stdin_source_reads_the_prompt_file(tmp_path):
    prompt_path = tmp_path / "engineer_prompt.txt"
    prompt_path.write_text("stdin-delivered prompt", encoding="utf-8")
    adapter = op.CodexAdapter()
    fh = adapter.stdin_source(prompt_path)
    try:
        assert fh.read() == b"stdin-delivered prompt"
    finally:
        fh.close()


def test_codex_adapter_resume_argv_is_exactly_codex_exec_resume(tmp_path):
    adapter = op.CodexAdapter()
    argv = adapter.build_argv(
        prompt_path=tmp_path / "p.txt", worktree=tmp_path / "wt", model="gpt-5-codex",
        effort="high", budget_usd=None, extra_dirs=[tmp_path / "relay"],
        resume_session_id="thread_abc123",
    )
    # Contract section 2.3: resume keeps the same config flags as a fresh
    # dispatch; only the leading verb differs.
    # Verified against codex-cli 0.154.0: `exec resume` has no --cd,
    # --sandbox or --add-dir, so sandbox policy travels as -c overrides.
    assert argv[:5] == ["codex", "exec", "resume", "thread_abc123", "--json"]
    for flag in ("--cd", "--sandbox", "--add-dir"):
        assert flag not in argv
    assert ["-m", "gpt-5-codex"] == argv[argv.index("-m"):argv.index("-m") + 2]
    assert "model_reasoning_effort=high" in argv
    assert "sandbox_workspace_write.network_access=true" in argv
    assert 'sandbox_mode="workspace-write"' in argv
    assert f'sandbox_workspace_write.writable_roots=[{json.dumps(str(tmp_path / "relay"))}]' in argv
    assert argv[-1] == "-"


# --- AC-3: session id read from a fixture log --------------------------------

def test_codex_session_id_from_log_reads_thread_started(tmp_path):
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({"type": "thread.started", "session_id": "thread_xyz"}) + "\n"
        + json.dumps({"type": "item.completed", "item": {"type": "message", "text": "done"}}) + "\n",
        encoding="utf-8",
    )
    adapter = op.CodexAdapter()
    assert adapter.session_id_from_log(log_path) == "thread_xyz"


def test_claude_session_id_from_log_reads_system_init(tmp_path):
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({"type": "system", "subtype": "init", "session_id": "s1", "model": "claude-sonnet-5"}) + "\n",
        encoding="utf-8",
    )
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.session_id_from_log(log_path) == "s1"


def test_observed_model_returns_none_none_for_a_missing_log(tmp_path):
    adapter = op.CodexAdapter()
    assert adapter.observed_model(tmp_path / "no-such-log") == (None, None)
    adapter2 = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter2.observed_model(tmp_path / "no-such-log") == (None, None)


def test_claude_observed_model_reads_system_init_model(tmp_path):
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({"type": "system", "subtype": "init", "model": "claude-sonnet-5"}) + "\n",
        encoding="utf-8",
    )
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.observed_model(log_path) == ("claude-sonnet-5", None)


def test_codex_observed_model_reads_thread_started_model_and_effort(tmp_path):
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({"type": "thread.started", "session_id": "t1", "model": "gpt-5-codex",
                     "model_reasoning_effort": "high"}) + "\n",
        encoding="utf-8",
    )
    adapter = op.CodexAdapter()
    assert adapter.observed_model(log_path) == ("gpt-5-codex", "high")


def test_claude_budget_exhausted_reads_terminal_reason_and_cost(tmp_path):
    """NXS-LOCAL-0126: the provider's own terminal signal, not an inferred
    cost-vs-limit comparison."""
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({
            "type": "result", "subtype": "error_max_budget_usd", "is_error": True,
            "terminal_reason": "budget_exhausted",
            "errors": ["Reached maximum budget ($3)"], "total_cost_usd": 3.014217,
        }) + "\n",
        encoding="utf-8",
    )
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.budget_exhausted(log_path) == {"cost_reached_usd": 3.014217}


def test_claude_budget_exhausted_none_for_an_ordinary_result_event(tmp_path):
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({"type": "result", "subtype": "success", "total_cost_usd": 0.02}) + "\n",
        encoding="utf-8",
    )
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.budget_exhausted(log_path) is None


def test_claude_budget_exhausted_none_for_a_missing_log(tmp_path):
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.budget_exhausted(tmp_path / "no-such-log") is None


def test_codex_budget_exhausted_always_none_no_proven_signal(tmp_path):
    """No confirmed Codex event stream field exists for this yet -- per the
    vendor-semantics law, absence of a proven signal is `None`, not an
    invented equivalence."""
    log_path = tmp_path / "engineer.log"
    log_path.write_text(
        json.dumps({"type": "error", "message": "budget exceeded"}) + "\n",
        encoding="utf-8",
    )
    adapter = op.CodexAdapter()
    assert adapter.budget_exhausted(log_path) is None


# --- AC-4: summarize_line never raises, one summary per fixture item --------

CODEX_JSON_LINES_FIXTURE = [
    '{"type":"thread.started","session_id":"thread_abc"}',
    '{"type":"item.started","item":{"type":"command_execution","command":"pytest -q"}}',
    '{"type":"item.completed","item":{"type":"command_execution","command":"pytest -q"}}',
    '{"type":"item.completed","item":{"type":"agent_message","text":"All green."}}',
    '{"type":"turn.completed"}',
    '{"type":"some_future_codex_event","payload":{"a": 1}}',
    '',
    'not json at all',
    '{"type":"error","message":"boom"}',
]


def test_codex_summarize_line_never_raises_and_yields_one_summary_per_recognized_item():
    adapter = op.CodexAdapter()
    results = [adapter.summarize_line(raw) for raw in CODEX_JSON_LINES_FIXTURE]
    # Blank and invalid-JSON lines are skipped (None); every other line
    # produces exactly one summary string, including the unrecognized
    # event type and the malformed line -- fixtures 2 (blank) and
    # 3 ("not json") are the only Nones expected.
    assert results[6] is None  # blank line
    assert results[7] is None  # not valid JSON
    non_none = [r for r in results if r is not None]
    assert len(non_none) == len(CODEX_JSON_LINES_FIXTURE) - 2
    assert all(isinstance(r, str) for r in non_none)


def test_codex_summarize_line_falls_back_for_an_unrecognized_shape():
    adapter = op.CodexAdapter()
    raw = '{"type":"some_future_codex_event","payload":{"a":1}}'
    assert adapter.summarize_line(raw) == raw


def test_codex_summarize_line_handles_blank_and_invalid_json_without_raising():
    adapter = op.CodexAdapter()
    assert adapter.summarize_line("") is None
    assert adapter.summarize_line("   ") is None
    assert adapter.summarize_line("not json") is None


# --- factory -----------------------------------------------------------------

def test_build_adapter_returns_the_right_class(tmp_path):
    assert isinstance(op.build_adapter("claude", profile_path=tmp_path / "p.json"), op.ClaudeAdapter)
    assert isinstance(op.build_adapter("codex"), op.CodexAdapter)


def test_build_adapter_rejects_an_unknown_provider():
    with pytest.raises(ValueError):
        op.build_adapter("gemini")


# --- GOV.ORCH.4 section 3.1: usage_from_event -------------------------------

def test_claude_usage_from_event_system_init_yields_model_kind():
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    obj = {"type": "system", "subtype": "init", "model": "claude-sonnet-5"}
    assert adapter.usage_from_event(obj) == {"kind": "model", "model": "claude-sonnet-5"}


def test_claude_usage_from_event_assistant_carries_message_id_and_usage():
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    obj = {"type": "assistant", "message": {"id": "msg_1", "usage": {
        "input_tokens": 10, "cache_creation_input_tokens": 2, "cache_read_input_tokens": 3, "output_tokens": 4,
    }}}
    normalized = adapter.usage_from_event(obj)
    assert normalized["kind"] == "assistant"
    assert normalized["message_id"] == "msg_1"
    assert normalized["usage"] == {
        "input_tokens": 10, "cache_creation_input_tokens": 2, "cache_read_input_tokens": 3, "output_tokens": 4,
    }


def test_claude_usage_from_event_result_carries_total_cost_usd():
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    obj = {"type": "result", "usage": {"input_tokens": 1, "output_tokens": 2}, "total_cost_usd": 0.5}
    normalized = adapter.usage_from_event(obj)
    assert normalized["kind"] == "result"
    assert normalized["total_cost_usd"] == 0.5
    assert normalized["usage"]["input_tokens"] == 1
    assert normalized["usage"]["cache_creation_input_tokens"] == 0


def test_claude_usage_from_event_ignores_unrelated_events():
    adapter = op.ClaudeAdapter(profile_path=Path("/dev/null"))
    assert adapter.usage_from_event({"type": "user"}) is None


def test_codex_usage_from_event_turn_completed_maps_cached_input_tokens():
    adapter = op.CodexAdapter()
    obj = {"type": "turn.completed", "usage": {"input_tokens": 5, "cached_input_tokens": 6, "output_tokens": 7}}
    normalized = adapter.usage_from_event(obj)
    assert normalized == {
        "kind": "turn",
        "usage": {
            "input_tokens": 5, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 6, "output_tokens": 7,
        },
    }


def test_codex_usage_from_event_thread_started_with_model_yields_model_kind():
    adapter = op.CodexAdapter()
    obj = {"type": "thread.started", "model": "gpt-5-codex"}
    assert adapter.usage_from_event(obj) == {"kind": "model", "model": "gpt-5-codex"}


def test_codex_usage_from_event_ignores_unrelated_events():
    adapter = op.CodexAdapter()
    assert adapter.usage_from_event({"type": "item.completed", "item": {}}) is None
