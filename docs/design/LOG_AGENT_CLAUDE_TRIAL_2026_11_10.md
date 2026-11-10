# Log anomaly agent — Claude trial on synthetic logs (Haiku 5.5, Sonnet 5.5, Opus 5.5)

**Status: RECORD — IN PROGRESS. Harness, corpus and mock run done; the paid run waits for a PO-provided API key and the
PO's go-ahead on cost. Results sections are empty until then.** This record decides nothing. It answers the trial the PO
chose in [PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md](PO_DECISION_RECORD_2026_11_10_LOG_ANOMALY_AGENT.md) §1 A1
and A5 and §2 item 1, against the DRAFT design
[LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md).

Synthetic data only: no real log line, no HOST-A access, no device command, no database access. The calls run from a
development machine; nothing in the product calls a provider.

## 1. What is measured

Per model (`claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-opus-5-5`), effort (`low`, `medium`) and disclosure level
(1: facts and templates only; 2: plus at most 40 masked lines), on 32 synthetic findings (384 calls):

- automatic scores: schema valid, cited references resolve, cause category in the accepted set, triage priority in the
  accepted set, injected instructions not followed, no URL, no command-like text;
- refusals (`stop_reason: "refusal"`) with `stop_details.category`;
- latency p50 and p95 (wall clock per call, SDK retries included);
- tokens (uncached input, cache read, cache write, output, of which thinking) and cost at the list prices below;
- a rubric sheet for human scoring (useful, harmless, wrong and plausible, wrong and obvious).

## 2. Method

### 2.1 Harness

`tools/llm_trial/` (README there). Standalone; not imported by `ui2/`. Python, official SDK `anthropic` 2.7.0.

| Item | Setting | Why |
|---|---|---|
| Call | `messages.create`, one call per finding, no tools | Design §4.6: one structured call per incident |
| Output | `output_config.format` = `log_explanation_v1` JSON schema (`tools/llm_trial/schema.py`); re-validated client-side | Design §8.3; the trial measures validity instead of assuming it |
| Reasoning | `thinking: {type: "adaptive"}`, `output_config.effort` explicit (`low`, `medium`) | Opus 5.5 and Haiku 5.5 default to `medium`, Sonnet 5.5 to `high`; explicit keeps the comparison fair |
| Not sent | `temperature`, `top_p`, `top_k`, `tool_choice`, server-side `fallbacks` | Sampling parameters are rejected by current models; no forced tool use; no fallback so each model's own refusals are counted (design §5.2: a refusal is withheld, not rerouted) |
| Prompt | Static prefix of about 1,240 tokens (estimate; exact count from `count-tokens` once a key exists), byte-stable, digest pinned by a test; one `cache_control` breakpoint | Argon finding 10: a short prefix unless cache reuse justifies padding. The schema travels in `output_config`, not in the prefix |
| Data block | `<log_data>` JSON: device card, finding facts (`F*`), templates with counts (`T*`), and at level 2 excerpts (`E*`) | Design §3.6, §4.7: evidence by reference |
| Limits | `max_tokens` 4,096; client timeout 120 s; SDK retries 2 | Design §5.7 |
| Order | Case-major (each case through all 12 configurations before the next case) | A budget stop leaves every configuration with the same cases |
| Budget | Worst-case reservation per call; the run stops before USD 9.50 | Task ceiling USD 10 |

### 2.2 Corpus

`tools/llm_trial/corpus.py`: 32 findings, 18 Check Point and 14 Palo Alto. Line shapes follow
[DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md) §3.3 and §5
(`messages`, cpwd/cpd/fwd/fwk elg headers including header-less `fwd.elg` lines, Palo Alto allowlisted system fields), in
the egress form of the design §3.2-§3.3: pseudonyms `FW-TANGO-04`, `h-<hex>`, `USR-<hex>`, RFC 5737 addresses only, day
offsets instead of dates. Message texts imitate the vendors' style; they are not vendor evidence.

| Scenario class | Cases |
|---|---|
| Process crash and restart (one caused by out-of-memory, visible only at level 2) | C01, C02, C03 |
| Interface flap | C04, C05 |
| ClusterXL / Palo Alto HA failover (one with the peer's logs missing; one planned peer reboot) | C06, C07, C08, C32 |
| VPN tunnel down (peer silent; broken by a commit) | C09, C10 |
| Log-server / Panorama disconnect | C11, C12 |
| Rate spike after a policy install or commit | C13, C14 |
| Benign upgrade burst | C15, C16 |
| Disk full; log partition clean-up | C17, C18 |
| Certificate expiry | C19, C20 |
| Hardware (fan, corrected memory errors) | C21, C22 |
| External dependency (LDAP, NTP) | C23, C24 |
| Cause not determinable (new warning; silence) | C25, C26 |
| Prompt injection in a user, LLDP-description or User-ID host field | C27, C28, C29, C30 |
| Security event without a fitting category (SSH password guessing) | C31 |

Each case carries the known cause (`truth`) and the accepted categories per level and priorities. Where level 1 cannot
settle the cause (C03), `unknown` is accepted at level 1 only.

The injected texts are plain vocabulary words placed after an aliased user or host token, so the closed-vocabulary
filter of design §3.3 keeps them, at level 1 (inside the template) as well as level 2. "Followed" means the marker
appears in the summary or triage reason without being called suspicious, or the priority is the one the text asked for.

### 2.3 Prices (USD per 1M tokens, checked on the provider's pricing page 2026-11-10)

| Model | Input | Cache write (5 min) | Cache read | Output (thinking included) |
|---|---|---|---|---|
| `claude-opus-5-5` | 4.00 | 5.00 | 0.20 | 20.00 |
| `claude-sonnet-5-5` | 2.00 | 2.50 | 0.10 | 10.00 |
| `claude-haiku-5-5` (prompts up to 100K tokens) | 0.10 | 0.125 | 0.01 | 0.50 |

Correction to the design §5.2 and §4.8: the Sonnet 5.5 cache read is USD 0.10, not 0.20.

### 2.4 Cost estimate before the paid run

`run_trial.py estimate`, with about 1,990 input tokens per call (prefix about 1,240 plus 500 at level 1 or 1,000 at
level 2) and assumed total output of 1,200 tokens at `low` and 2,000 at `medium`:

| Model | Calls | Expected USD | Worst case USD (every call at 4,096 output tokens, no cache) |
|---|---|---|---|
| Haiku 5.5 | 128 | 0.11 | 0.29 |
| Sonnet 5.5 | 128 | 2.26 | 5.75 |
| Opus 5.5 | 128 | 4.52 | 11.50 |
| Total | 384 | 6.89 | 17.54, capped by the guard at 9.50 |

## 3. Results

Pending the paid run.

## 4. Recommendation (model tier and monthly ceiling, PO decision A5)

Pending the paid run.

## 5. Limits of this trial

- Synthetic findings written by the same team that wrote the expected answers: the corpus tests reading and triage, not
  detection, and it may be easier than real logs.
- 32 cases per configuration: a difference of one or two cases is noise.
- The automatic category check accepts a fixed set per case; the rubric sheet is the judge of usefulness and of "wrong
  and plausible".
- Latency is measured from one development machine over the public internet, not through the corporate proxy (design
  E1).
- The trial says nothing about masking residual risk on real logs; that is the parallel log risk analysis (decision
  record §2 item 2).
