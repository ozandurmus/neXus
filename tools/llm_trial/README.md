# LLM trial harness

Standalone harness for the paid-model trial of log-finding explanations (Haiku 5.5, Sonnet 5.5, Opus 5.5; effort
low/medium; disclosure level 1/2). It measures quality, refusals, tokens, latency and cost. It is **not product
code**: nothing under `ui2/` may import it, and it is not wired into the product.

**Synthetic data only.** The corpus (`corpus.py`) holds invented findings with documentation IP ranges and
pseudonymised names. Never feed real device data to this harness.

## Commands (repo root)

```
python3 tools/llm_trial/run_trial.py estimate                      # offline call count and cost
python3 tools/llm_trial/run_trial.py run --provider mock --out /private/tmp/llmtrial-mock
python3 tools/llm_trial/run_trial.py report --in /private/tmp/llmtrial-mock
~/Codo/.venv-llmtrial/bin/python tools/llm_trial/run_trial.py count-tokens --out DIR   # free endpoint
~/Codo/.venv-llmtrial/bin/python tools/llm_trial/run_trial.py run --provider claude --out DIR --yes-paid-run
```

Filters on estimate / count-tokens / run: `--models`, `--efforts`, `--levels`, `--cases C01,C02`, `--max-tokens`.
`run` has `--budget-usd` (default 9.50; worst-case reservation per call, the run stops at the first call that
would not fit), `--workers`, and `--resume` (skips calls already recorded with a non-ERROR status).

## Credentials

Set `ANTHROPIC_API_KEY` (or `ANTHROPIC_AUTH_TOKEN`) in the shell, or run `ant auth login`. Never put a key in a
file, a commit or a CLI argument; the harness never prints or stores it. `--provider claude` refuses to start
without `--yes-paid-run`. Only the Claude provider imports the `anthropic` SDK (installed in
`~/Codo/.venv-llmtrial`); tests and the mock provider need only the standard library.

## Output (`--out DIR`)

`results.jsonl` (one call per line with its score), `run_meta.json`, `cases_snapshot.json`, then from `report`:
`summary.md`, `rubric_full.csv`, `rubric_blind.csv` (model and effort hidden, for the human scorer),
`rubric_blind_key.csv`. Tests: `python3 -m pytest -q tools/tests/test_llm_trial.py`.

## Committed results

`results/run-2026-11-10/` holds the paid run of 2026-11-10 (record:
`docs/design/LOG_AGENT_CLAUDE_TRIAL_2026_11_10.md`). The raw results are committed gzipped; to rebuild the report and
`rubric_full.csv`: `gunzip -k results/run-2026-11-10/results.jsonl.gz`, then
`python3 tools/llm_trial/run_trial.py report --in tools/llm_trial/results/run-2026-11-10`. The paid run was driven by a
local script that reads the key with hidden input and keeps it only in that process's environment.
