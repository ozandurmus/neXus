# UI 2.0 — B1-5: CP inventory extraction contract (narrow subset)

**DRAFT — FOR PRODUCT OWNER FREEZE, 2026-09-11.**

This is the contract for `UI2_0_DEVELOPMENT_WORKFLOW.md` §5 Phase B1 row 5:
"Extract: CP inventory, narrow subset (pattern step a, Line-1 read-only) —
spec + fixtures for a validated subset (`show version`, HA state) from
`cp_inventory.sh`/`cp_runner.py`; channel-drain quirk recorded as `UNKNOWN`
with a validation plan; the subset is chosen for being small and validated,
not for quirk richness (Astra 4.6)." Row 6 ("Implement: CP inventory
capability … first Java capability end to end against fixtures;
`CAP-OFFLINE`") and the PO validation session (step c) consume this
document's output; neither is performed here.

## 1. Scope and authority

This movement is an **`Extract`** (workflow §3.3 step a): it reads Line-1
Python/shell source as a reference and produces one committed capability
specification plus one committed fixture set under the `ui2/` tree. Stated
plainly and bindingly:

- **This movement writes no Java.** No `ui2/*.java` source, no module, no
  interface.
- **This movement contacts no device.** No SSH session is opened, no
  credential is read, no vendor CLI command is issued against any real or
  simulated Check Point gateway.
- **This movement does not change Line-1.** `checkpoint/scripts/cp_inventory.sh`,
  `checkpoint/cp_runner.py`, `checkpoint/direct_ssh_probe.py`, and every
  other Line-1 file named below are read only; nothing under `checkpoint/`,
  `configuration/`, or `utils/` is edited by this movement.

Authority, highest first: `AGENTS.md` (identity law, UNKNOWN/fail-closed
law, raw-evidence law, the network-device command gate's ten fields,
privacy/DLP); `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) —
`RUNTIME-DIRECTION`, `FIRST-CAPABILITY` (this exact subset, ACCEPTED,
channel-drain stays `UNKNOWN`, blocks `CAP-VALIDATED` only if shown to
affect completeness); `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.1/§3.3/
§4/§5 Phase B1 rows 5–6; `docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_
CONTRACT.md` (FROZEN) — the procedure this document executes and the
`FIRST-CAPABILITY` worked example (§4) this document turns into a real,
committed spec and fixture set; `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_
GATE_RESOLUTION_CONTRACT.md` (FROZEN) — the registry schema and field-state
model the spec must populate; `docs/design/UI2_0_B1_04_COLLECTION_ENGINE_
CORE_CONTRACT.md` (DRAFT) — the parser framework and evidence writer this
spec feeds, and its open item 1 (spec file format, not yet fixed); `docs/
design/PRIVATE_REPLAY_ARCHITECTURE.md` — the tokenizer path; `scripts/
ui2_extract_fixtures.py` — the existing Line-1 extraction tool this
movement invokes rather than a new mechanism.

This document does not reopen `FIRST-CAPABILITY`'s scope, does not redefine
the `C4` schema, and does not author any `gate_registry` row (gate
authorship is a separate, named act — §5 below).

## 2. The chosen subset, and why

`FIRST-CAPABILITY` (baseline §2, ACCEPTED) names the subset explicitly: CP
Gaia `show version` and HA state, on a standalone or ClusterXL-member
gateway, no VSX context. Concretely, two reads:

1. **`show version` / `show version all`** — the vendor's product-version
   report, extracted field: `product_version`.
2. **`cphaprob stat`** — the vendor's cluster/HA-role report, extracted
   field: `ha_state`.

Both are read-only (`action_class` 0, `utils/action_taxonomy.py`).

**Deliberately excluded, though present in the same Line-1 neighbourhood:**

- **Interface and route enumeration** (`ip -details -4 addr show`, `ip -4
  route show table all`) — `cp_inventory.sh`'s actual payload (§3.1 below).
  Excluded because it is a separate semantic unit from version/HA identity
  and was never named by `FIRST-CAPABILITY`; pulling it in would turn one
  narrow extraction into a second, unscoped one.
- **VSX context enumeration** (`checkpoint/vsx_runner.py`,
  `checkpoint/vsx_parser.py`) — workflow §4 row 2's own future extraction;
  `FIRST-CAPABILITY` is explicitly non-VSX (`target_shape_ref.virtual_
  system_ref = NOT_APPLICABLE`, `C4` §4.5).
- **The wider HA readiness battery** (`cphaprob syncstat`, `cphaprob -a if`,
  `cphaprob -ia list`, `cphaprob tablestat`) — `checkpoint/cp_preflight_
  battery.py`'s `A4`–`A9` items and the still-`UNPROVEN` `tablestat` case
  (workflow §4 row 6, movement 0049 pending). Richer in quirks than this
  row, which is exactly why it is excluded here: workflow §5's own row text
  states the subset is "chosen for being small and validated, not for
  quirk richness" (Astra 4.6) — a deliberate ordering choice, not an
  oversight that a richer battery was left out.
- **CP Gaia configuration snapshot** (`configuration/checkpoint_config_
  collector.py`) — workflow §4 row 3, `C4` §5.2's own named future
  candidate for the `ssh_interactive` evidence bar; a different transport
  question this row does not need to answer.
- **Direct-SSH interfaces/routes probe** (`checkpoint/direct_ssh_probe.py`'s
  `interfaces`/`routes` families) — read only as corroboration of the
  transport and command-literal shape for `version` (§3.2 below), not
  extracted as part of this capability's own steps.

## 3. What the Line-1 collector actually does, for these two reads

### 3.1 `cp_inventory.sh` / `cp_runner.py` — what they actually collect

`checkpoint/scripts/cp_inventory.sh` (read in full) is a bounded-parallel,
multi-gateway batch collector. Per enumerated gateway (`collect_gateway()`,
lines 176–292) it issues exactly two remote commands via
`$CPDIR/bin/cprid_util … rexec -rcmd bash -c "<command>"`
(`run_live_command()`, lines 101–169): `ip -details -4 addr show` and `ip -4
route show table all`, plus a third, conditional command, `cphaprob -a -m
if` (line 269), issued only when the object type is `cluster_member` — this
reports configured **cluster interfaces**, not the HA role. **Neither
`show version` nor `cphaprob stat` appears anywhere in this script.** This
is stated plainly rather than guessed around: `cp_inventory.sh` is the
interface/route/cluster-interface collector, and it is *not* the direct
source of `FIRST-CAPABILITY`'s two commands. `checkpoint/cp_runner.py`
orchestrates this script as a single uploaded-and-executed unit over one
persistent SSH session (`_run_remote_collection`, lines 286–383) and is the
**primary source for the channel-drain quirk** (§4 below), independent of
which commands the script happens to run.

### 3.2 Where `show version` and `cphaprob stat` actually come from

- **`show version`** — `checkpoint/direct_ssh_probe.py::READ_ONLY_COMMANDS
  ["version"]` (lines 26–31) names the literal command family: `"show
  version all"`, `"show version"`, `'clish -c "show version all"'`,
  `'clish -c "show version"'`, tried in that order by `_run_command_family`
  (lines 178–197) until one succeeds. Each is issued through
  `_run_session_command` (lines 86–175): a fresh `transport.open_session()`
  per command, a PTY requested (`channel.get_pty(...)`, best-effort), then
  `channel.exec_command(command)` — i.e. **`ssh_exec`-shaped**, one-shot
  per command, not a shared interactive shell carrying state across
  commands. Success/failure is read from `channel.exit_status_ready()`
  plus non-empty stdout plus the absence of a CLI-error-pattern match
  (`_looks_like_cli_error`, lines 81–83) — there is no application-level
  "done" marker text for this per-command path; completion is the SSH
  channel's own exit-status signal.
- **`cphaprob stat`** — `checkpoint/cp_preflight_battery.py` names it as
  `A3_CPHAPROB_STAT` (line 65) with wire command `"cphaprob stat"` (line
  84), issued once per (enumerated) target inside a verified shell context.
  This corroborates the literal command string; it does not itself
  establish the transport shape for `FIRST-CAPABILITY`'s two-command
  subset, which is instead taken from `direct_ssh_probe.py`'s
  one-shot-per-command pattern (§5 field 2 below).

### 3.3 The channel-drain quirk, as observed

`checkpoint/cp_runner.py::_run_remote_collection` (lines 286–383) opens
**one** `ssh.exec_command()` channel for the entire `cp_inventory.sh`
invocation across all enumerated gateways, and reads its stdout/stderr in a
polling loop (`_drain_ready`, lines 311–329) until `channel.exit_status_
ready()` is true and both buffers are drained. It additionally tracks a
literal `DONE` marker line (`_process_collection_output_line`, lines
239–241) the shell script prints as its own last line (`cp_inventory.sh`
line 401, `echo "DONE"`), and treats an exit status of 0 **without** that
marker as a hard failure (lines 365–372: `"CP remote collection ended
without DONE marker"`) — never inferring success from exit status alone.
This is the documented finding: a batched, long-running, multi-gateway
collection over one channel needs an explicit completion marker because
exit status plus channel drain was, at some point in this product's
history, judged insufficient on its own to prove the collector actually
finished emitting everything before returning.

## 4. The channel-drain quirk — recorded as `UNKNOWN`, with a validation plan

**Existence of the quirk: `KNOWN`.** It exists, is documented, and its
mechanism is exactly as described in §3.3, source `checkpoint/cp_runner.py::
_run_remote_collection` (`state["done_marker_seen"]`).

**Effect on this capability's own narrow subset: `UNKNOWN`.** What is
observed: the quirk's evidence base is the *batched, multi-gateway* `bash -l
<script>` invocation over one long-lived channel. What is **not** known:
whether the same completion-ambiguity applies to `FIRST-CAPABILITY`'s two
**one-shot**, per-command `exec_command` calls, each on its own fresh
channel (`direct_ssh_probe.py::_run_session_command`'s pattern, §3.2), which
already treats `channel.exit_status_ready()` plus a drained buffer as
sufficient completion evidence for a single command's own output — no
`DONE` marker mechanism is used or needed there, because there is no
multi-item batch to lose track of. It is not established here that this
per-command completion signal is equally trustworthy for `show version`/
`cphaprob stat` on every real CP Gaia release and shell configuration this
product supports (e.g. output arriving in more than one flush burst,
delayed final packets on a slow session). Guessing either way — "the quirk
applies" or "it clearly doesn't" — is the exact invented-certainty failure
`AGENTS.md`'s UNKNOWN/fail-closed law and workflow R-04 forbid; it is
recorded `UNKNOWN` instead.

**Validation plan** (this field's mandatory companion, workflow §3.1 field
9, `C4` §2.6):

1. During the PO validation session (workflow §3.3 step c, baseline B1 step
   6), the Java `cp_gaia_inventory_show_version_ha_state` capability is run
   against the registered test CP gateway (`B1-4b`'s `is_test_target` flag)
   with per-step transcript logging enabled at the transport-adapter level
   (channel `recv_ready()`/`exit_status_ready()` timing, byte counts per
   read, and elapsed time between last data and exit-status arrival).
2. The PO reviews that transcript for: (a) whether stdout ever arrives in
   more than one burst after `exit_status_ready()` first reports true, and
   (b) whether either command's total round-trip time on this gateway class
   approaches or exceeds the timeout bound field 3b of the spec (§5 below)
   leaves `UNKNOWN`.
3. **Evidence that resolves this field:** a small number (at minimum three)
   of real-environment runs of both commands against the registered test
   target, with no observed post-exit-status data arrival and stable
   round-trip timing, resolves field 6b to `NOT_APPLICABLE` (structural: the
   one-shot `exec_command` completion signal is sufficient for this
   subset) with the transcript evidence named as the rationale. Any single
   observed instance of data arriving after `exit_status_ready()` first
   reported true resolves it instead to a still-open, but now
   *characterized*, `UNKNOWN` naming the observed failure shape, and blocks
   `CAP-VALIDATED` per baseline §2's `FIRST-CAPABILITY` ruling ("if it
   affects result completeness it blocks `CAP-VALIDATED`").
4. **Who can produce this evidence:** only the Product Owner, per the
   standing rule that device-contacting commands are proposed by the agent
   and executed by the human (workflow §3.3, "c is always the PO's own
   session"). No agent session may run this validation step itself.

This field's `UNKNOWN`/`validation_plan_ref` pairing must appear verbatim in
the committed spec — `C6` §6 criterion 12 makes this a named, spec-schema-
checked assertion specific to this `capability_id`, not merely a documented
intention.

## 5. The capability specification

Filled against `C4` §2.2's registry columns, via `C6` §2.1's field mapping.
This mirrors `C6` §4.1's worked example field-for-field (that section is
illustrative prose inside a frozen contract, not a committed spec); this
section is what `B1-5` actually commits, at
`ui2/specs/cp_gaia_inventory_show_version_ha_state.<format>` (format per
`B1-04`'s open item 1, unresolved — YAML proposed, pending PO confirmation,
§12 below).

| Field | Value | State | Rationale / validation plan |
|---|---|---|---|
| `capability_id` | `cp_gaia_inventory_show_version_ha_state` | KNOWN | matches `C4` §2.5's and `C6` §4.1's naming |
| `vendor` | `check_point` | KNOWN | — |
| `platform_role_scope` | `cp_gaia_gateway` | KNOWN | `FIRST-CAPABILITY` is gateway-scoped, not management |
| `action_class` | `read` (class 0) | KNOWN | both commands are read-only vendor CLI reads, `utils/action_taxonomy.py` |
| `maturity_state` | `CAP-SPEC` | KNOWN | this movement's own exit state; `CAP-OFFLINE` is row 6's, not this one's |
| `transport.kind` | `ssh_exec` | KNOWN | `C4` §5.1 default; no Line-1 evidence of cross-command shell-state dependency — `direct_ssh_probe.py::_run_session_command` opens a fresh channel per command (§3.2); `C4` §5.2's evidence bar (a captured transcript proving a later command needs earlier shell state) is not met and is not claimed |
| `transport.trust_rule_ref` | `utils.cp_ssh_trust` | KNOWN | Line-1's existing strict host-key preflight (`apply_strict_host_key_policy`, imported by `direct_ssh_probe.py` line 16) |
| `target_shape_ref.device_id`/`endpoint_id` | always present | KNOWN | `C4` §4.2, physical identity always carried |
| `target_shape_ref.virtual_system_ref` | `NOT_APPLICABLE` | NOT_APPLICABLE | `FIRST-CAPABILITY` is explicitly scoped non-VSX (baseline §2, `C4` §4.5) — structural exclusion, not an open gap |
| `target_shape_ref.cluster_member_ref` | `KNOWN` per-member when the target is a ClusterXL member; `NOT_APPLICABLE` for a standalone gateway | mixed, per-field | `C4` §4.3: a cluster run is two independent jobs, two independent evidence rows, never merged |
| `steps[1]` | `{kind: connect}` | NOT_APPLICABLE (gate) | `ssh_exec` connect gate is auth + optional banner only, `C4` §2.3 |
| `steps[2]` | `{kind: exec, send: "show version all", action_class: read}` | `gate_reference`: **UNKNOWN: requires gate entry** | no `gate_registry` row exists yet for `(check_point, cp_gaia_gateway, *, ssh_exec, "show version all")`; validation plan: a gate-authorship step (§1.2 of `C6`, out of this document's own authority) must produce a `docs/AI_DEVELOPMENT_PROTOCOL.md`-compliant ten-field entry before row 6 |
| `steps[2].timeout_s` | not yet fixed | UNKNOWN | validation plan: the PO validation session (§4 above) captures real observed latency against the registered test gateway before row 6 hardcodes a bound |
| `steps[3]` | `{kind: exec, send: "cphaprob stat", action_class: read}` | `gate_reference`: **UNKNOWN: requires gate entry** | no `gate_registry` row exists yet for `(check_point, cp_gaia_gateway, *, ssh_exec, "cphaprob stat")`; same validation plan as `steps[2]` |
| `steps[3].timeout_s` | not yet fixed | UNKNOWN | same validation plan as `steps[2].timeout_s` |
| `finally_steps[1]` | `{kind: disconnect}` | NOT_APPLICABLE (gate) | `KNOWN` step content, no gate applies to `disconnect` (`C4` §2.3) |
| `parser_ref.parser_version` | not yet assigned | UNKNOWN | validation plan: row 6 (`Implement`) assigns the first `parser_version` at `CAP-OFFLINE` |
| `parser_ref.semantics_doc_ref` | this section's own prose (§6 below) | KNOWN | extracted fields, tolerant-parsing intent, and evidence-of-truth rule are recorded now even though the exact regex is `UNKNOWN` (below) |
| `produces_facts[]` | `[{fact_id: "ha_state:<target_ref>"}, {fact_id: "product_version:<target_ref>"}]` | KNOWN | this is the first capability to touch either fact — `C6` §2.2 step 2's produces-facts pre-check finds no existing producer, so both are named here rather than referenced |
| `consumes_facts[]` | `[]` | NOT_APPLICABLE | this capability is the producer of both facts, not a consumer of any (structural exclusion) |
| `evidence_shape_ref.projection_table_ref` | `cp_inventory_projection` | KNOWN | `C1` §3.4's existing sketch already carries exactly `product_version`/`ha_state` as its two derived-fact columns, sized to this subset |
| `evidence_shape_ref.discard_raw` | `true` | KNOWN | workflow §3.5, `R-06`; only a provenance record and a permitted sanitized fragment are retained, never the raw device response |
| `known_quirks[]` (existence) | channel-drain / `DONE`-marker dependency in the batched multi-gateway collector | KNOWN | `checkpoint/cp_runner.py::_run_remote_collection` (§3.3) |
| `known_quirks[]` (effect on this subset) | whether the same drain ambiguity applies to the two one-shot `exec_command` reads this capability actually uses | **UNKNOWN** | §4's validation plan, verbatim |
| `source_pointers[]` | `checkpoint/scripts/cp_inventory.sh` (context: what the batched collector actually runs, §3.1 — named as corroboration of the channel-drain evidence base, not as the source of the two commands themselves); `checkpoint/cp_runner.py::_run_remote_collection` (primary source, channel-drain quirk); `checkpoint/direct_ssh_probe.py::READ_ONLY_COMMANDS["version"]`, `::_run_session_command`, `::_run_command_family` (primary source, the `show version` command family and the one-shot-per-command transport shape); `checkpoint/cp_preflight_battery.py` (`A3_CPHAPROB_STAT`, corroborates the `cphaprob stat` literal) | KNOWN | read, never imported (`RUNTIME-DIRECTION`) |
| `validation_status` | `REAL_ENV_VALIDATED` (channel-drain quirk open) | KNOWN | matches workflow §4 row 1's own inherited status; `direct_ssh_probe.py`'s one-shot pattern and `cp_preflight_battery.py`'s `cphaprob stat` usage are both Line-1-operational, not merely fixture/test-only |
| `revalidation_plan_ref` | the PO validation session described in §4 above (start the Java capability against the registered test gateway; confirm both gate rows resolve `KNOWN`; confirm the timeout bound holds; resolve field 6b to `NOT_APPLICABLE` or a characterized, still-open `UNKNOWN`) | KNOWN | the plan is concrete even though its outcome is not yet known |

Every field carries an explicit state; no field is left ambiguous between
`UNKNOWN` and `NOT_APPLICABLE` (`C6` §2.2 step 3, `AC-3`/`AC-4` of `C6` §6).

## 6. Parser semantics (prose, `semantics_doc_ref`)

- **Extracted fields:** `product_version` (from `show version all`'s
  version-line output), `ha_state` (from `cphaprob stat`'s local-role
  line).
- **Tolerant-parsing intent:** whitespace and minor version-string drift
  across CP Gaia releases must not fail parsing outright; the exact
  tolerant regex is **UNKNOWN**, pending real-environment capture review at
  this movement's own fixture-authoring step (§8) and finalized at row 6.
- **Failure classification:** `OUTCOME_UNKNOWN` when the channel-drain
  effect (§4, field 6b) is later shown to apply and no completion signal is
  observed; `partial`/`refused` classifications otherwise follow `C2`'s
  step-executor rules, not re-derived here.
- **Evidence-of-truth rule:** `product_version` links to the `show version
  all` raw response line that proved it; `ha_state` links to the `cphaprob
  stat` raw role line that proved it; each via its own `provenance_records`
  row (`C1` §5), never a bare hash standing in for retrievable raw content
  (workflow §3.5).

## 7. Fixtures

Fixtures are produced by `scripts/ui2_extract_fixtures.py` (already
committed Line-1 tooling implementing `C6` §3) — this movement does not
invent a parallel extraction mechanism. Invocation shape, once per command
per capture session, sharing one `--capture-session-id` so cross-output
identity equality holds (`C6` §3.3):

```
python scripts/ui2_extract_fixtures.py \
    --capture-dir <staged, already-sanitizable capture directory> \
    --output-dir ui2/fixtures/cp_gaia_inventory_show_version_ha_state \
    --capability-id cp_gaia_inventory_show_version_ha_state \
    --command-tuple "show version all" \
    --vendor-version <real observed release string> \
    --capture-date <date of the underlying real capture> \
    --capture-session-id S1
```

repeated with `--command-tuple "cphaprob stat"` and the same
`--capture-session-id` for the second fixture from the same gateway/session.

**Fixture set produced**, under `ui2/fixtures/cp_gaia_inventory_show_
version_ha_state/`:

1. **REAL** — a sanitized `show version all` capture from one real,
   already-collected CP Gaia gateway.
2. **REAL** — a sanitized `cphaprob stat` capture from the same gateway and
   session (`capture_session_id: S1`) — proves cross-output identity
   equality.
3. **DERIVED** — a version-drift variant of fixture 1 (`fixture_kind:
   DERIVED`, `derived_from`, `derived_change: "product_version field
   only"`), using a second real CP Gaia release string already observed on
   a different gateway.
4. **SYNTHETIC** — an empty-output `show version all` failure path
   (`fixture_kind: SYNTHETIC`, `synthetic_reason`: exercises the parser's
   `OUTCOME_UNKNOWN` classification path for a response that never
   populated), matching `direct_ssh_probe.py`'s own `empty_output` error
   class (§3.2).
5. **REAL, ClusterXL pair** — two `cphaprob stat` captures from a real
   two-member cluster, one per member, sharing one grouping token distinct
   from their per-member device tokens and carrying distinct `ha_state`
   values, tagged `MEMBER_SPECIFIC` (`C4` §4.3) — proves ClusterXL member
   identity is preserved without collapsing to one merged fact.

**Sanitization mechanism:** `Tokenizer.token()`/`Tokenizer.network_token()`
under an HMAC key from `_get_support_key()`, invoked exclusively through
`scripts/ui2_extract_fixtures.py`, which runs the DLP privacy gate
(`utils.repository_privacy.scan_repository`, the same scanner
`main.py --repository-privacy-check` runs) against a staging directory
**before** copying anything into `--output-dir`; a finding refuses the
write outright (the tool's own documented behaviour, verified by reading
its source, §"the DLP gate is mandatory and blocking").

**Absolute rule, stated once, binding:** no committed fixture may contain a
device name, a management IP address, a serial number, a hostname, or any
other customer identifier, in any form — tokenized identity references are
the only representation of identity a fixture may carry, and version
strings (not treated as sensitive identity, per `C6` §4.2 fixture 1) are the
only clear-text field.

**How sanitization is verified:**

1. `scripts/ui2_extract_fixtures.py`'s own run refuses to write on any DLP
   finding (mandatory, not optional — see above).
2. Independently, before this movement's commit: `python3
   scripts/repository_privacy_check.py` run against the full working tree,
   including the new fixture directory, exactly as it runs against any
   other file (`C6` §3.5 — "there is no 'it's just a test fixture'
   exemption").
3. A human (this movement's own self-check, and the PR reviewer) inspects
   every committed fixture file's `command_tuple`, `vendor_version`, and
   payload fields by eye for anything the automated scan's known field list
   might miss — the DLP gate is a backstop, not a substitute for the
   extracting agent's own care in choosing what capture to sanitize.

## 8. Test specification

| Test | Fails when |
|---|---|
| `test_fixture_set_parses` | Any committed fixture file under `ui2/fixtures/cp_gaia_inventory_show_version_ha_state/` is not valid JSON/the fixture schema, or is missing mandatory metadata (`command_tuple`, `vendor_version`, `capture_date`, `fixture_kind`, `capture_session_id`) |
| `test_unknown_field_yields_unknown_not_default` | The SYNTHETIC empty-output fixture, run through the capability spec's declared parser semantics (prose stub at this stage; row 6's real parser once it exists), produces any concrete `product_version`/`ha_state` value instead of an explicit `OUTCOME_UNKNOWN` / unset-with-provenance-gap result — proving an absent field is never silently defaulted |
| `test_cross_output_identity_equality` | Fixtures 1 and 2 (same `capture_session_id`) do not carry byte-identical tokenized device-identity fields |
| `test_clusterxl_members_not_collapsed` | Fixture 5's two ClusterXL-member captures share a grouping token but do **not** carry distinct member/device tokens and distinct `ha_state` values, or a query path merges them into one row |
| `test_spec_every_field_has_explicit_state` | Any `C4` §2.2 field in the committed spec is missing, or its state is implicit/omitted rather than one of `KNOWN`/`UNKNOWN`/`NOT_APPLICABLE` |
| `test_unknown_fields_carry_validation_plan` | Any field marked `UNKNOWN` in the spec has an empty or missing `validation_plan_ref` |
| `test_not_applicable_fields_carry_rationale` | Any field marked `NOT_APPLICABLE` has an empty or missing `state_rationale`, or reuses identical rationale text as an `UNKNOWN` field on the same capability |
| `test_gate_reference_naming_discipline` | Either `exec` step's `gate_reference` is anything other than the literal `NOT_APPLICABLE` or the literal `UNKNOWN: requires gate entry` — in particular, fails if a free-text `gate_id` is invented where none is source-committed |
| `test_channel_drain_field_present_and_paired` | The spec's field-6b state/`validation_plan_ref` pairing (§4) is missing, or is silently resolved to `KNOWN`/`NOT_APPLICABLE` without a recorded PO validation session artifact |
| `test_fixture_sanitization_gate_passes` | `python3 scripts/repository_privacy_check.py` exits non-zero against the working tree with the new fixture directory present |
| `test_no_off_queue_capability_claimed` | The committed spec's `capability_id` is not exactly `cp_gaia_inventory_show_version_ha_state`, or claims a scope beyond `FIRST-CAPABILITY` (e.g. VSX or an interfaces/routes field) |

Each test proves only what its row states: a passing `test_fixture_set_
parses` proves the fixture files are well-formed and complete on their
declared metadata, not that they represent every real-world CP Gaia output
shape; a passing `test_unknown_field_yields_unknown_not_default` proves the
SYNTHETIC path is classified correctly, not that the eventual Java parser's
tolerant-regex handling is correct (that is row 6's own test surface).

## 9. Acceptance criteria

1. **AC-1.** The committed spec's `capability_id` is exactly
   `cp_gaia_inventory_show_version_ha_state`, matching workflow §4 row 1 and
   `C6` §4.1/§5 row 1 verbatim.
2. **AC-2.** Every `C4` §2.2 field in the committed spec carries an explicit
   `KNOWN`/`UNKNOWN`/`NOT_APPLICABLE` state; none is omitted or implicit.
3. **AC-3.** Every `UNKNOWN` field carries a non-empty `validation_plan_ref`
   naming a concrete, executable next step.
4. **AC-4.** Every `NOT_APPLICABLE` field carries a non-empty
   `state_rationale` distinct from any `UNKNOWN` field's rationale on the
   same spec.
5. **AC-5.** Both `exec` steps' `gate_reference` reads the literal `UNKNOWN:
   requires gate entry`; no `gate_id` is invented.
6. **AC-6.** The channel-drain quirk's existence is `KNOWN` with
   `checkpoint/cp_runner.py::_run_remote_collection` cited by name and line
   range; its effect on this subset is `UNKNOWN` with the §4 validation
   plan reproduced verbatim in the spec.
7. **AC-7.** The fixture set contains exactly the five fixtures of §7,
   correctly tagged `REAL`/`DERIVED`/`SYNTHETIC`, with `DERIVED`/`SYNTHETIC`
   metadata fields present exactly where required and absent elsewhere.
8. **AC-8.** No fixture file, spec file, or this document itself contains a
   device name, management IP address, hostname, serial number, or other
   customer identifier; `python3 scripts/repository_privacy_check.py`
   passes against the full working tree including the new files.
9. **AC-9.** Every behavioural claim about Line-1 in this document cites a
   specific file and function (or line range); no vendor behaviour is
   asserted without a source pointer or an explicit `UNKNOWN`.
10. **AC-10.** No `ui2/*.java` file, no Flyway migration, and no `gate_
    registry` row is created by this movement; `git diff --stat` against
    the commit shows only the spec file, the fixture directory, and this
    contract document.
11. **AC-11.** `maturity_state` in the committed spec is `CAP-SPEC`, never
    `CAP-OFFLINE` or beyond — this movement does not advance maturity past
    what an `Extract` step is authorized to reach (workflow §3.3 step a).

## 10. Validation plan (runnable commands)

```
python3 -m pytest -q -p no:cacheprovider tests/test_architecture_convergence.py tests/test_cold_start_budget.py
python3 scripts/repository_privacy_check.py
git diff --check
```

Device-contacting validation (the PO validation session, §4's channel-drain
plan and workflow §3.3 step c) is **not** part of this movement's own
validation plan — it is row 6's and the PO's, named here only as the
`revalidation_plan_ref` the committed spec must carry.

## 11. Worker route and effort

**Sonnet 5, normal**, with one extended-thinking check on the channel-drain
vendor-semantic call (§4) before commit. This is deterministic source audit
and spec-template population against two already-frozen contracts (`C4`,
`C6`) and one already-built tool (`scripts/ui2_extract_fixtures.py`) — no
new architecture, no Java, no device contact — matching `CLAUDE.md`'s
routing rule ("Sonnet 5, normal for source audit … deterministic
implementation against a frozen contract"). `Sonnet 5, extended thinking
(high)` would be more than this step needs for the mechanical parts, but is
the right tier for the single vendor-semantic judgment this document makes
explicit rather than silently defaulting (§4's field 6b determination that
it must be recorded, not resolved, here).

## 12. Open items for the Product Owner

1. **Spec file format is still open** (`UI2_0_B1_04_COLLECTION_ENGINE_CORE_
   CONTRACT.md` open item 1, inherited unchanged). This document assumes a
   YAML file at `ui2/specs/cp_gaia_inventory_show_version_ha_state.yaml`
   populated per §5's table; a different serialization the PO prefers
   changes only the file's syntax, not any field or state in §5.
2. **Gate-entry authorship for both commands is a separate, preceding act**
   this document does not perform (`C6` §1.2). `steps[2]`/`steps[3]` stay
   `UNKNOWN: requires gate entry` until a `docs/AI_DEVELOPMENT_PROTOCOL.md`-
   compliant ten-field entry exists for each of `(check_point,
   cp_gaia_gateway, *, ssh_exec, "show version all")` and `(check_point,
   cp_gaia_gateway, *, ssh_exec, "cphaprob stat")`. Whether that authorship
   happens as its own preceding movement or is folded into row 6 is for the
   PO to decide; it is not decided here.
3. **The real capture source for the fixture set is not named here.** This
   document specifies the fixture *shape* and the tool that produces it;
   which already-collected, already-authorized evidence/capture directory
   supplies the underlying real `show version`/`cphaprob stat` output (a
   single real gateway plus one real two-member cluster) is an operational
   detail for whoever executes this movement, not a policy decision for
   this contract.
4. **The channel-drain field's ultimate resolution is explicitly not
   pre-judged** (§4) — this document commits to recording it `UNKNOWN` with
   a validation plan, per `FIRST-CAPABILITY`'s own ruling; it does not
   guess, and does not ask the PO to guess, which way the PO's own
   real-environment run will resolve it.
5. **Timeout defaults for both `exec` steps remain unset** (§5,
   `steps[2].timeout_s`/`steps[3].timeout_s`), consistent with `UI2_0_B1_
   04_COLLECTION_ENGINE_CORE_CONTRACT.md`'s own open item 4 (no gate-sourced
   default exists for a step's timeout until a gate entry supplies one) —
   not fixed here, and not fixable here without inventing a number this
   document has no evidence for.
