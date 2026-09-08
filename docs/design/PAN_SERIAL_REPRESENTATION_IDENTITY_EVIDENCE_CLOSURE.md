# PAN serial representation / identity evidence closure — investigation

## Status

**INVESTIGATION — INCONCLUSIVE, AWAITING PRODUCT OWNER DECISION.** Not a
contract, not implementation authority, not a closure. This document is the
findings record for `project/backlog.json` id
`pan_serial_representation_identity_evidence_closure` (P0). It roots out what
source code and already-persisted state can show; it does not authorize any
identifier reinterpretation (`AGENTS.md` opaque-identifier law stands) and it
does not authorize implementing any fix it proposes.

Movement: `PAN_SERIAL_REPRESENTATION_IDENTITY_EVIDENCE_CLOSURE_INVESTIGATION`
(relay `NXS-LOCAL-0028`). Closes the OP.0b.0 contract's own `PAN-15` gap
(`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
§26) only in the sense of giving the finding a documented, reasoned home —
`PAN-2` ("runtime serial correspondence unmeasured") stays open, because the
measurement this document produces is still inconclusive.

## AC-1 — the code path, from source

Two fields, both computed in `configuration/panorama_config_collector.py`:

- `_apply_pan_ha_peer_identity_diagnostic(row)` (line ~1701) computes
  `self_identity_consistent` per device row, from the *already-fetched*
  `show high-availability state` response (no new command/call — see its own
  docstring).
- `_finalize_pan_runtime_peer_serial_correspondence(rows)` (line ~1763)
  computes `runtime_peer_serial_state` across every device row collected in
  the same run.

Four independent serial observations exist, per the frozen OP.0b.0 identity
contract's own vocabulary:

| Symbol | Source | Code |
| --- | --- | --- |
| I1 | Panorama managed-device discovery (`show devices all`) | `get_devices()` line 229: `entry.findtext("serial") or entry.get("name")` |
| I2 | direct `show system info` over the firewall's own API, identity-gated session | `_collect_direct_compare()` line 1580-1594: `get_direct_system_info(...)["serial"]`, compared against `device["serial"]` (I1); on mismatch the row is marked `identity_mismatch` |
| I3 | runtime `local-info/serial-num`, this device's own self-report in HA context | `_tokenize_ha_field_diagnostics()` line 254, tokenized under `"pan_ha_identity_value"` |
| I4 | runtime `peer-info/serial-num`, this device's one-sided claim about its peer | same function, same tokenization |

`self_identity_consistent` is `local_serial_token (I3) == identity_gated_serial_token`,
where `identity_gated_serial_token = tok.token("pan_ha_identity_value", row.get("serial"))`
(line 1749). `runtime_peer_serial_state` compares `peer_serial_token` (I4) of
each row against the set of every *other* row's `identity_gated_serial_token`
(the same `row.get("serial")`-derived token) — `MATCH` if `I4` is found among
those, `MISMATCH` otherwise, `MISSING`/`NOT_EVALUABLE` for the degenerate
cases. All correctly HMAC-tokenized; no raw serial is ever compared or
persisted (verified: `tests/test_phase0_6_0a4_3_3_2_workflow_and_ha.py`
lines 215-317 exercise every branch, including whitespace-padding equality at
line 253-262).

**Finding 1 (code fact, not yet flagged anywhere in the frozen contract or
the backlog note): `row.get("serial")` is I1, not I2.** Tracing
`_collect_device_row` (line ~1803): `row["serial"] = serial = device["serial"]`
is set at line 1832 straight from the `device` dict `get_devices()` built —
Panorama's own discovery cache, I1. `_apply_pan_ha_peer_identity_diagnostic(row)`
is invoked at line 1935, *inside the same function, before* `_collect_direct_compare`
runs at line 1977-1986 and writes its result into `row["direct"]` (a separate
key; it never overwrites `row["serial"]`). So at the moment
`identity_gated_serial_token` is computed, no I1==I2 cross-check has happened
yet on this row — the variable name (and this file's own test comment at
line 224-226, "matches this device's own identity-gated serial") both claim
a gating that, mechanically, has not occurred. Compare this file's own
`--pan-config-targets` CLI help text (`application/cli.py` line 121-122),
which defines "identity-gated serial" precisely as "cross-checked against
each firewall's own `show system info`" — i.e. I2 — confirming the naming
mismatch by the codebase's own vocabulary.

This does not, by itself, explain the S0 asymmetry (see AC-3): the backlog
note states both members were "directly identity-gated successfully" in the
same run, i.e. I1==I2 held for both members independently. But it is a real,
source-grounded finding worth fixing for clarity regardless of root cause,
and it means the identity contract's documented definition
("self identity verified: I1 == I2 ... I3 == I2 is a consistency check")
does not match what `self_identity_consistent` actually computes today
(I3 == I1).

## AC-2 — locating and re-deriving the S0 evidence

No raw or tokenized field-level artifact from the S0 run is persisted
anywhere reachable from source control or from this environment's runtime
root:

- `project/backlog.json` (`pan_serial_representation_identity_evidence_closure`)
  and `project/build_history.json`
  (`pan_ha_runtime_peer_identity_diagnostic`, `risks_forward`) both carry only
  the narrative summary — "one member MATCH/MATCH, the other MISMATCH/MISMATCH"
  — never the underlying I1/I2/I3/I4 tokens or raw values.
- Searched: every `project/*.json` file, `docs/history/**`, this worktree's
  git history (`git log -S`), and the local runtime root
  (`utils/runtime_paths.py` resolves to `~/.local/share/SecurityExpert/runtime`
  on this profile — "Repository is not runtime": all run artifacts live
  outside the repo by design). The one run directory present there
  (`data/runs/20260908_205527_c0691b60`) is an unrelated later collection
  against a large synthetic/lab fleet, not the S0 real PAN pair, and contains
  no `self_identity_consistent`/`runtime_peer_serial_state` hits at all.
- The S0 real-environment run happened on the separate laptop environment
  referenced by `on_hardware_real_env_validation` (§26 `X-6`, "BLOCKED on
  laptop availability") — its runtime root was never this repository's, and
  nothing from it was ever committed (by design: `AGENTS.md` privacy/DLP law
  forbids persisting raw device identity to git, and this diagnostic only
  ever surfaces booleans/enum states, never the tokens themselves, to any
  output — see `runtime_peer_identity_evidence` shape, which drops
  `_peer_serial_token`/`_identity_gated_serial_token` once resolved).

**AC-2 cannot be literally satisfied.** There is no raw evidence to
re-derive MATCH/MISMATCH from by hand — only the code path that *would*
compute it, and the already-resolved narrative conclusion. This is itself
the load-bearing finding for everything below: the diagnostic was designed,
correctly per the sensitive-identity-reporting law, to persist only the
final enum state and never the comparison inputs — which means the one time
its output was surprising (asymmetric MATCH/MISMATCH across two members),
nothing was captured that could explain *why*. That is a real evidence-model
gap, independent of whatever the underlying root cause turns out to be.

## AC-3 — ruling in/out the three mismatch classes

**Representation divergence (different string formatting between
self-report and peer-report extraction).** Traced both extraction paths to
the same XML `root` object PAN's API response, via two different but
consistently-normalized readers: `_pan_ha_group_text()` (`.strip()`,
`None`-on-absent) and `_tokenize_ha_field_diagnostics()` (`(child.text or
"").strip()`), plus `get_devices()`'s I1 read (also plain `.strip()`). All
three normalize identically — no case-folding, no numeric coercion, no
padding removal anywhere in any of the three paths. This matches and
confirms the backlog note's existing "whitespace difference and parser
numeric conversion are both ruled out by source inspection." **Representation
divergence from a parsing bug in this codebase is ruled out.** A vendor-side
representation difference between what `show devices all` reports as
`<serial>` and what `show high-availability state` reports as
`local-info/serial-num` for the *same* device cannot be ruled out from code
alone — no official PANW source confirms these two fields are always
byte-identical for one device (see below).

**Genuine runtime identity discrepancy (the devices actually disagree).**
Two independent, real, already-established anomalies specific to *the same
physical member* (the OP.0b.0 contract calls it "member B", the passive
member of the approved real pair) predate and are separate from this S0
result:
- `PAN-3` (§26): "member B configured/runtime HA1 inconsistency (real);
  model cannot represent it" — member B's configured `peer-ip` "matches no
  runtime address field at all" ("Why this contract exists" section).
- `PAN-12` (§26): "passive member's `peer-info` matched nothing —
  completeness unexplained" (real pair).

Both are prior, independently-confirmed, real-environment findings — not
representation bugs, not this movement's own conjecture. They establish that
member B has a standing, unresolved runtime/config irregularity on the exact
same `peer-info`/HA-identity surface the serial diagnostic reads. That
doesn't prove the serial MISMATCH shares the same cause, but a persistent,
role-correlated (member B is the passive member in both) irregularity is a
more parsimonious explanation than a formatting bug in code that treats both
members identically and produced a clean result for member A in the same
run.

**Another semantic mismatch class.** The OP.0b.0 contract's own freeze
record leaves `D-V3a` — "HA-state serial field semantics" — explicitly
**`STILL_UNKNOWN`** even after freeze ("Final semantic blocker closure"
section: "this session found no serial field at all in the one official HA
state example it could read"). That means: whether `local-info/serial-num`
and `peer-info/serial-num` in `show high-availability state` are officially
guaranteed to echo the same value as `show devices all`'s `<serial>` (I1) or
`show system info`'s `<serial>` (I2), for every PAN-OS version/HA
mode/role this estate might be running, is **not confirmed by any source
this repository has been able to reach** (three sessions hit an identical
`WebFetch`-class block against `pan.dev`/Check Point/PANW hosts — "Risks"
section). A role- or version-dependent field-population difference (e.g. the
passive member populating `local-info/serial-num` from a different internal
source than the active member) would produce exactly this asymmetric
MATCH/MATCH-vs-MISMATCH/MISMATCH pattern without either device's identity
actually being wrong.

**Conclusion on AC-3:** representation divergence *from this codebase's
parsing* is ruled out. Between "genuine identity discrepancy" and "another
(vendor) semantic mismatch class", the evidence leans toward the latter,
compounded by (or possibly indistinguishable from, without more evidence) a
genuine member-B-specific irregularity that predates this diagnostic — but
neither is provable from what's persisted. See AC-6.

## AC-4 — the 2026-09-04 manual observation

The 2026-09-04 update (OP.0b S8-C session) records a manually-captured
`show high-availability all` output reportedly showing reciprocal serial
correspondence on both members — apparently contradicting the S0 MISMATCH on
member B. This document does **not** treat it as resolving anything, for
reasons independent of (and additional to) the backlog note's own caveat:

1. **Different command.** `show high-availability all` is not
   `show high-availability state` — the command this codebase's collector
   actually issues (`get_target_ha_runtime_state`, line 528). Nothing in this
   repository parses `show high-availability all` or has confirmed its XML
   shape exposes `local-info/serial-num`/`peer-info/serial-num` at the same
   path, or at all. A human match on a differently-shaped CLI text dump is
   not evidence that the *code's own comparison* would also find a match.
2. **Different capture method.** It was a human's visual read of terminal
   output, "outside the approved S8-C P1/P2/P4 battery, not runtime-authorized"
   per the note itself — no tokenization, no provenance stamp
   (`preflight_run_id`, `collected_at`, `source_command`; see the OP.0b.0
   "Provenance contract"), nothing this document can mechanically re-check.
3. **Different time.** Captured on 2026-09-04, after the S0 run. The
   OP.0b.0 contract's own peer relationship contract explicitly warns:
   "Serial corroboration, when it lands, does not erase B's [HA1 address]
   inconsistency" — the two axes are independent and the document treats
   them as capable of diverging at different times without either being
   wrong. If member B's identity/HA-state field population is at all
   role-, timing-, or event-dependent (e.g. tied to an HA state transition),
   a clean read days later doesn't retroactively explain a MISMATCH read
   earlier.

**Remains unreconciled.** Neither confirms nor refutes the S0 finding; it is
lower-fidelity, differently-sourced, differently-timed evidence that cannot
be mechanically cross-checked against the automated result. Per this
movement's invariants, the original MATCH/MISMATCH finding is preserved,
not overwritten.

## AC-5 / AC-6 — root cause and disposition

**Root cause cannot be determined from already-persisted evidence and
source code alone.** Two independent, compounding reasons: (a) no raw
per-field evidence from the S0 run was ever persisted (AC-2), so the actual
I1/I2/I3/I4 values for member B are simply not available to inspect; (b) the
vendor semantics of the exact fields being compared are officially
unconfirmed (`D-V3a`, `STILL_UNKNOWN`, frozen contract), so even a full code
audit cannot establish whether the code's comparison is measuring what it
assumes it measures.

**What additional evidence would resolve it** — a single, bounded, read-only
capture, run once, against the two already-approved real PAN pair devices,
by the Product Owner (this session must not and did not attempt live device
contact):

```
py main.py --only pan-config --pan-config-targets <serial_A>,<serial_B> --pan-ha-peer-diagnostic
```

- `--pan-config-targets` is the existing exact-serial allowlist (`OP.0d`,
  `application/cli.py` line 116-128) — fail-closed, no guess, scoped to
  exactly these two devices, no new command.
- `--pan-ha-peer-diagnostic` is the existing opt-in CLASS 0 diagnostic that
  already computes `self_identity_consistent`/`runtime_peer_serial_state`
  for both members in one coherent run — the exact result S0 already
  produced once. Re-running it does not by itself resolve anything new; see
  the proposed instrumentation below.
- This targets a specific answer the current diagnostic literally cannot
  surface today: because only the final MATCH/MISMATCH enum persists, a
  second run reproduces the same shape of evidence gap AC-2 hit. **Before
  re-running**, the Product Owner should decide whether to separately
  authorize a small, additive instrumentation change (not made here, per
  scope) that persists which *pairwise* relationship actually breaks for
  member B — I1 vs I2, I1 vs I3, or I2 vs I3 individually, still as booleans
  only, never raw values — so a second MISMATCH is diagnosable instead of
  being exactly as opaque as the first.
- If the Product Owner additionally wants role/version context: capture
  member B's `local-info/state` (active/passive) and `sw-version` in the
  same run — both already parsed, already persisted, no new field.

**Proposed closure fix direction (AC-5, contingent, not authorized to
implement):** independent of root cause, `identity_gated_serial_token`
should be renamed/re-sourced to actually depend on I2 (e.g. threading
`row["direct"]["identity_verified"]`/the verified serial through to the
diagnostic, or reordering so the diagnostic runs after `_collect_direct_compare`)
so the code matches both its own name and the frozen identity contract's
stated `I3 == I2` definition. Tradeoff: this is a real behavior change to a
CLASS-0 evidence diagnostic that today never blocks anything — reordering or
re-sourcing it needs the same Product Owner sign-off any identity-evidence
gate semantics change needs, and on the evidence above would not have
changed the S0 result (I1==I2 already held for both members that run), so it
should not be presented as "the fix" for PAN-2/B2 — only as an accuracy
correction, separately scoped.

## Disposition

Per AC-6: this movement does not close `pan_serial_representation_identity_evidence_closure`.
`RELAY_NOTE`, `AWAITING_PO`, appended to `relay/NXS-LOCAL-0028-*.json` with
the specific question above (authorize the bounded capture command, and
decide on the optional instrumentation addition, before the next PAN HA
identity-evidence movement). `project/backlog.json`'s note is updated (not
overwritten) with this document's pointer and a compressed version of these
findings.
