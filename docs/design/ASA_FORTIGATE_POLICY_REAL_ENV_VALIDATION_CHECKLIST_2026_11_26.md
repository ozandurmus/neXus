# ASA and FortiGate policy model — real-environment validation checklist (2026-11-26)

**Status: DRAFT -- checklist for the PO's real-environment run; not a contract; decides nothing.** It changes no
contract, decision record or project state. Backlog item `asa_fortigate_policy_model` (P1) stays
`AUTOMATED_VALIDATED` until the Product Owner reports the completed run (this checklist is not real-environment
evidence, and the CLI conformance test that pins it proves only that its command forms parse and call the documented
routes).

Controlling documents: `docs/design/ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` (section 8, "Real-environment validation
(separate gate, after step 6): 20 masked lookups per vendor chosen by the PO, compared with the device's own answer ...
Without it the build stays AUTOMATED_VALIDATED"; section 10 Q8, the PO chooses the sample units),
`docs/design/PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md`,
`docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` (FROZEN; its status line decides its
authority; rows `A0-ASA-n`, `A0-FG-n`), `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` (answer 2,
`ACL_NOT_BOUND`) and `docs/design/P1_COUNTS_ONLY_RUN_SHEET_2026_11_26.md` section 6 (stored-policy counts; referenced,
not copied: that sheet measures what the parser and mapper make of the stored artefacts, this checklist samples what
the product answers and compares it with the owner's own answer). CLI forms were copied from
`ui2/cli/src/main/java/com/securityexpert/nexus/ui2/cli/PolicyOpsCli.java`, `PolicyCli.java`, `PathTopologyCli.java` and
`NexusSessionCli.java` on `origin/main` at `8a304479`; the executable is `nexus-cli` (`overwatch-cli` is the same
program under its new name). Every step names the design or record clause it proves.

## 0. What this run touches, and what it does not

- **No device contact.** Lookup, hygiene, HA pair counting, Trace and the viewer all read stored snapshots and the held
  topology. The run is read-only, `aiview` only (`role:replay_viewer`, masked projection), and Overwatch makes zero
  device writes.
- **The comparison with "the device's own answer" is the PO's own out-of-band step.** This document records only the
  verdict token per sample and **names no device command**. Two reasons: an ad hoc device command sent through
  Overwatch needs the Product Owner's review and approval of the exact code, command, target scope and output
  projection before execution (`AGENTS.md`, PO Amendment 2026-09-26, ad hoc parser diagnostics), and approval of one
  command does not approve the next; and the FortiOS equivalent of the ASA read is only the DRAFT
  `docs/design/FORTIGATE_POLICY_LOOKUP_VALIDATION_READ_PROPOSAL_2026_11_23.md` (`DRAFT -- DO NOT FREEZE`), which is
  **pending** the PO's exact-literal approval and is not cited here as approval of anything. Whoever produces the
  device's answer (the owner's own tooling or a separately approved read) is outside this checklist; only the token,
  the counts and the closed reason codes come back.
- **Report the relationship, not the values** (`AGENTS.md`, Sensitive identity reporting law and Mandatory AIView
  inspection law). Every evidence token below is a boolean, a count, a closed enum word, a masked rule number, an opaque
  id or a verdict token. Never write a real hostname, address, serial, interface (`nameif`) name, VDOM name, ACL or
  object name, rule body, comment or device output into this record, a ticket or chat. Examples here are synthetic
  (`FW-TANGO-04`, `192.0.2.0/24`, `198.51.100.0/24`).
- **Out of scope:** the zone matrix half of the item (waits for the PO's answers to
  `ZONE_MATRIX_ASA_FORTIGATE_AMENDMENT_PROPOSAL_2026_11_25.md`, DRAFT); the counts-only harness runs of the P1 run sheet
  section 6; any new, changed or ad hoc device command.

---

## 1. Preconditions (tick each Yes / No)

| # | Precondition | How to check (route and schema facts only) | Yes / No |
|---|---|---|---|
| PR1 | Active `aiview` CLI session (`role:replay_viewer`, masked view) | `aiv session` (`GET /session/status`) exits `0` and reports the `replay_viewer` / masked session | |
| PR2 | At least one stored ASA policy and one stored FortiGate policy exist | `aiv policy-devices` (`GET /api/v2/policy/devices`) exits `0` and lists a policy with `"vendor": "ASA"` and one with `"vendor": "FGT"` (an ASA context or a FortiGate VDOM is one policy); `aiv policy-sources` (`GET /api/v2/policy/sources`) shows their last collection. Record the counts only | |
| PR3 | The deployed build contains design steps 4 to 8 (viewer, normalization, lookup, hygiene with pair counting, Trace) | Steps 4 and 5-6: the lookup form in L-A1 answers a verdict word and a `lookupReason`, not an error; step 7: `aiv policy-hygiene-summary` (`GET /api/v2/policy/hygiene-summary`) carries `haPairsCountedOnce`, `haPairsDivergent`, `haPairsUnknown` and `aiv policy-ha-pairs` (`GET /api/v2/policy/ha-pairs`) exits `0`; step 8: T-A1 and T-F1 return a hop whose `vendor` is `ASA` or `FGT`. Verify against the deployed build, never from a tag name alone | |
| PR4 | The PO has chosen the masked sample units (design section 10 Q8 is open for the ACI redirect targets: **the PO decides which units; this checklist does not**) and keeps the sample tuples in a local file under `$WORK`, never in a record | PO statement | |
| PR5 | The PO has a way to obtain the owner's own answer per sample, outside this checklist and outside Overwatch (see section 0) | PO statement | |
| PR6 | The held topology contains at least one ASA hop and one FortiGate hop for a tuple the PO can also look up | `aiv topology` (`GET /api/v2/topology`) exits `0` and reports a held snapshot | |

Stop at any "No" in PR1 to PR3: the run cannot start. PR4 or PR5 "No": run only the steps that need no device answer
(counts in H-A1, H-F1, H-P1, V1) and mark the comparison steps `NOT_RUN` (`no_sample_units` / `no_device_answer`). PR6
"No": mark T-A1 and T-F1 `NOT_RUN` (`no_held_hop`).

---

## 2. Setup and read-only helpers

Run once in a scratch directory that is deleted at the end (step X01). Placeholders in angle brackets are filled only in
your local shell and never copied into the build record. All CLI arguments are positional; `-` skips an optional
positional argument so the server's default applies.

```bash
WORK=$(mktemp -d)
aiv() { NEXUS_CLI_HOME="$WORK/aiview" nexus-cli "$@"; }       # aiview (replay_viewer) session only
aiv login <baseUrl> <aiviewAccount> local                      # password: NEXUS_CLI_PASSWORD or prompt
aiv session > "$WORK/session.json"
aiv policy-devices > "$WORK/devices.json"
aiv policy-sources > "$WORK/sources.json"
# One stored ASA policy id and one stored FortiGate (VDOM) policy id, opaque ids from devices.json:
ASA_POLICY_ID="<asaPolicyId>"
FGT_POLICY_ID="<fgtPolicyId>"
```

The PO's sample files hold one masked tuple per line: `source destination service sourceInterface destinationInterface`,
with `service` as `any`, `tcp/<port>` or `udp/<port>` (the form `policy-lookup` accepts). They are written by the PO
under `$WORK` and never leave it. For ASA the two interface fields are the ingress and egress interface names (ASA
interface binding is required and never inferred); for FortiGate they are the optional `srcintf` / `dstintf` inputs
(`-` leaves one out). `policy-lookup` is a read-only `POST` whose JSON body carries these fields; it changes nothing.

---

## 3. Step tables

### Step L-A — Twenty masked ASA lookups against the owner's answer

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| L-A1 | One sample as a smoke test: `aiv policy-lookup "$ASA_POLICY_ID" 192.0.2.10 198.51.100.20 tcp/443 <sourceInterface> <destinationInterface>` (`POST /api/v2/policy/policies/{id}/lookup`) | Exit `0`; the body carries `verdict` (`ALLOWED`, `DENIED`, `UNKNOWN` or `PARTIAL`) and `lookupReason`; the reason is a member of the closed list in section 4 | Design steps 5-6, section 8; A0-ASA-1 to A0-ASA-5 | `lookup_smoke_exit=0`, `lookup_reason_in_closed_list=<bool>` | |
| L-A2 | For each of the PO's twenty ASA samples, run the same form from the sample file and keep the answer under `$WORK`: the loop below | Twenty answers; a lookup never turns an interface or zone into an inferred value: without both interface fields the product answers `UNKNOWN` (`zone or target required`), never a guess | Design section 8 (20 masked lookups per vendor); A0-ASA-4a/4b/4c; PO decision record 2026-11-14 item 4 | `asa_samples=<n>` (target 20) | |
| L-A3 | The PO (or the interface owner) obtains the owner's answer per sample out-of-band and records the token per sample in the table below | One token per sample, from the four defined in section 4 | Design section 8 | `asa_match=<n>`, `asa_mismatch=<n>`, `asa_unknown=<n>`, `asa_unknown_both=<n>`, `asa_unknown_device_definite=<n>`, `asa_device_unavailable=<n>` | |

```bash
while read -r SRC DST SVC SIF DIF; do
  aiv policy-lookup "$ASA_POLICY_ID" "$SRC" "$DST" "$SVC" "$SIF" "$DIF" >> "$WORK/asa-answers.jsonl"
done < "$WORK/asa-samples.txt"
```

| Sample | Product `verdict` | `lookupReason` (closed list only) | Owner's answer (`ALLOW`, `DENY`, `INCONCLUSIVE`, `NONE`) | Token (`MATCH`, `MISMATCH`, `UNKNOWN_BOTH`, `DEVICE_ANSWER_UNAVAILABLE`, or `-`) |
|---|---|---|---|---|
| A01 | | | | |
| A02 | | | | |
| A03 | | | | |
| A04 | | | | |
| A05 | | | | |
| A06 | | | | |
| A07 | | | | |
| A08 | | | | |
| A09 | | | | |
| A10 | | | | |
| A11 | | | | |
| A12 | | | | |
| A13 | | | | |
| A14 | | | | |
| A15 | | | | |
| A16 | | | | |
| A17 | | | | |
| A18 | | | | |
| A19 | | | | |
| A20 | | | | |

### Step L-F — Twenty masked FortiGate lookups against the owner's answer

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| L-F1 | One sample as a smoke test, without interface inputs: `aiv policy-lookup "$FGT_POLICY_ID" 192.0.2.10 198.51.100.20 tcp/443 - -` | Exit `0`; `verdict` and `lookupReason` present; reason in the closed list | Design steps 5-6, section 11 ("Lookup": one policy table per VDOM, first match in printed order, implicit deny) | `lookup_smoke_exit=0`, `lookup_reason_in_closed_list=<bool>` | |
| L-F2 | For each of the PO's twenty FortiGate samples run the loop below (one VDOM policy per run; the PO may mix VDOMs across the twenty) | Twenty answers; an implicit-deny answer carries `no rule matched; implicit deny` only when the implicit section holds the implicit deny alone, otherwise the fall-through reads `UNKNOWN` | A0-FG-1 to A0-FG-3, A0-FG-10; design section 11 | `fgt_samples=<n>` (target 20) | |
| L-F3 | Owner's answer per sample out-of-band, token per sample in the table below | One token per sample | Design section 8 | `fgt_match=<n>`, `fgt_mismatch=<n>`, `fgt_unknown=<n>`, `fgt_unknown_both=<n>`, `fgt_unknown_device_definite=<n>`, `fgt_device_unavailable=<n>` | |

```bash
while read -r SRC DST SVC SIF DIF; do
  aiv policy-lookup "$FGT_POLICY_ID" "$SRC" "$DST" "$SVC" "$SIF" "$DIF" >> "$WORK/fgt-answers.jsonl"
done < "$WORK/fgt-samples.txt"
```

| Sample | Product `verdict` | `lookupReason` (closed list only) | Owner's answer (`ALLOW`, `DENY`, `INCONCLUSIVE`, `NONE`) | Token (`MATCH`, `MISMATCH`, `UNKNOWN_BOTH`, `DEVICE_ANSWER_UNAVAILABLE`, or `-`) |
|---|---|---|---|---|
| F01 | | | | |
| F02 | | | | |
| F03 | | | | |
| F04 | | | | |
| F05 | | | | |
| F06 | | | | |
| F07 | | | | |
| F08 | | | | |
| F09 | | | | |
| F10 | | | | |
| F11 | | | | |
| F12 | | | | |
| F13 | | | | |
| F14 | | | | |
| F15 | | | | |
| F16 | | | | |
| F17 | | | | |
| F18 | | | | |
| F19 | | | | |
| F20 | | | | |

### Step L-V — FortiGate samples that depend on VIP or same-interface evaluation (recorded separately)

Two conformance pins are still `@Disabled` and fail closed to `UNKNOWN` until real FortiOS corroboration or a PO
ratification (`docs/history/backlog/asa_fortigate_policy_model.md`, 2026-11-26 open decisions): **A0-FG-11** (traffic
entering and leaving the same interface: evaluated as an ordinary lookup with `from = to`, a differing result is
`UNKNOWN`) and **A0-FG-19** (a policy whose destination is a VIP: the VIP cell is evaluated as its external address and
its mapped address in parallel, differing verdicts are `UNKNOWN`). The related `A0-FG-18` and `A0-FG-20` surface as the
reasons `FGT_VIP_PRIORITY_UNPROVEN` and `FGT_MATCH_VIP_UNPROVEN`. A sample is recorded here, **not** in the twenty of
L-F and **not** in the L-F counts, when its product answer carries one of those two reasons, when its destination is a
VIP's external address, or when the PO chose it to exercise a same-interface tuple. Up to five such samples; fewer is
recorded as the number found.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| L-V1 | `aiv policy-lookup "$FGT_POLICY_ID" 192.0.2.10 198.51.100.20 tcp/443 <sourceInterface> <destinationInterface>` for each separate sample, then the owner's answer out-of-band | Where evaluations differ the product answers `UNKNOWN` with a closed reason; it never picks one side | A0-FG-11, A0-FG-18, A0-FG-19, A0-FG-20; design section 11 | `fgt_vip_samples=<n>`, `fgt_vip_match=<n>`, `fgt_vip_mismatch=<n>`, `fgt_vip_unknown=<n>`, `fgt_vip_device_unavailable=<n>` | |

| Sample | Depends on (`A0-FG-11`, `A0-FG-19`, `A0-FG-18`, `A0-FG-20`) | Product `verdict` | `lookupReason` | Owner's answer | Token |
|---|---|---|---|---|---|
| V01 | | | | | |
| V02 | | | | | |
| V03 | | | | | |
| V04 | | | | | |
| V05 | | | | | |

A separate-table `MATCH` is **not** evidence that the two disabled pins can be enabled, and it never advances them: the
pins stay `@Disabled` until real FortiOS corroboration or a PO ratification, recorded by the PO.

### Step H — Hygiene for one ASA context and one FortiGate VDOM

Hygiene findings read the same stored snapshots as the viewer's Hygiene tab
(`?screen=policy&tab=hygiene` as `aiview`). Compare each count in the response with the same count the tab shows for the
same container (`MATCH` / `MISMATCH`), and verify the snapshot did not change between the two reads
(`collectedAt` equal).

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| H-A1 | `aiv policy-hygiene "$ASA_POLICY_ID" 0 - - 90` (`GET /api/v2/policy/policies/{id}/hygiene?page=0&days=90`) and, per finding class, `aiv policy-hygiene "$ASA_POLICY_ID" 0 shadowed - 90`, `aiv policy-hygiene "$ASA_POLICY_ID" 0 conflict - 90`, `aiv policy-hygiene "$ASA_POLICY_ID" 0 unused - 90` | Exit `0`; `coverage` (`rules`, `shadowProven`, `shadowUnknown`, `hitsCollected`, `hitsMissing`) and `counts` per class (`shadowed`, `conflict`, `disabled`, `unused`, `expired`, `broad`, `unknown`); `budgetReached` false, otherwise the counts are partial and the step reads `NOT_EVALUABLE` | Design step 7; A0-ASA-2, A0-ASA-16 | `asa_hygiene_counts_vs_tab=<MATCH\|MISMATCH>`, `asa_rules=<n>`, `asa_shadow_proven=<n>`, `asa_shadow_unknown=<n>`, `asa_budget_reached=<bool>`, plus the seven class counts as `asa_class_<name>=<n>` | |
| H-F1 | `aiv policy-hygiene "$FGT_POLICY_ID" 0 - - 90` and the same per-class forms for the FortiGate VDOM | As H-A1; one VDOM is one policy, so a FortiGate count never spans VDOMs | Design step 7, section 5.3 | `fgt_hygiene_counts_vs_tab=<MATCH\|MISMATCH>`, `fgt_rules=<n>`, `fgt_shadow_proven=<n>`, `fgt_shadow_unknown=<n>`, `fgt_budget_reached=<bool>`, plus `fgt_class_<name>=<n>` | |
| H-P1 | `aiv policy-ha-pairs` and `aiv policy-hygiene-summary` (`GET /api/v2/policy/hygiene-summary`) | An HA pair whose two members hold the same policy (`haOutcome` `SAME`, pair identity `MATCH`) is counted **once**: the second member's container row is omitted from the summary and `haPairsCountedOnce` equals the number of such pair contexts. A pair that differs reads `HA_POLICY_DIVERGENT` and is not merged; a pair that cannot be compared reads `UNKNOWN` with one of `MEMBER_POLICY_MISSING`, `PAIR_IDENTITY_NOT_MATCH`, `SNAPSHOT_HAS_COLLECTION_FAILURES`, `CONTEXT_SET_DIFFERS`. Pair existence is not pair health | Design 5.1, Q5; PO decision record 2026-11-14 decision 6; `AGENTS.md` Evidence laws | `ha_pairs=<n>`, `ha_same=<n>`, `ha_divergent=<n>`, `ha_unknown=<n>`, `ha_counted_once=<n>`, `ha_summary_rows_vs_stored_minus_counted_once=<MATCH\|MISMATCH\|NOT_EVALUABLE>` | |
| H-U1 | `aiv policy-hygiene "$ASA_POLICY_ID" 0 unused - 90` and `aiv policy-hygiene-summary`; read the ASA ACL entries that carry the `unused` finding | **`ACL_NOT_BOUND` is the record's name; the stored class is `unused`** with the evidence text `ACL has no access-group binding and no other use was found; it is not enforced as an access rule.` (PO 2026-11-23 definition: referenced by no access-group and by no other parsed feature: NAT, crypto map, VPN filter, class-map, route-map). An ACL named only by a configuration feature the parser does not read carries `ACL is named by a configuration feature the parser does not read; whether it is used is UNKNOWN.` and is **never** counted as `unused` or read as bound or unbound: an unparsed feature reads `UNKNOWN` | PO decision record 2026-11-23 answer 2; `PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` section 16; P1 run sheet section 6 | `acl_not_bound_count=<n>`, `acl_use_unknown_count=<n>`, `acl_not_bound_checked=<n>`, `acl_not_bound_owner_confirms_unreferenced=<n>`, `acl_not_bound_vs_owner=<MATCH\|MISMATCH\|NOT_EVALUABLE>`, `acl_unknown_counted_as_unbound=<bool>` (must be `false`) | |

For H-U1 the owner's own check is the same out-of-band step as in section 0: for each sampled `ACL_NOT_BOUND` entry the
owner confirms that nothing references that ACL. Record only how many were checked and how many the owner confirmed
unreferenced; a `MISMATCH` (an ACL the product calls unreferenced but the owner finds referenced) is a finding that
stops the `unused` wording for that class until fixed.

### Step T — Trace through one ASA hop and one FortiGate hop on a held topology

Trace evaluates a hop with the same lookup engine; for the same tuple and the same interfaces the hop's security
verdict must equal the lookup's verdict. Interface binding comes from inventory (ASA `nameif`, FortiGate interface):
present, or `UNKNOWN`, never inferred.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| T-A1 | `aiv path-query '{"src":"192.0.2.10","dst":"198.51.100.20","protocol":"tcp","port":443}'` (`POST /api/v2/path-analysis/query`) for a tuple the PO chose so that it crosses an ASA hop; find the hop whose `vendor` is `ASA` and read its `security.security_verdict`, `security.security_reason`, and `ingress.interface_name` / `egress.interface_name`; then run the lookup with the same tuple and those two interface names: `aiv policy-lookup "$ASA_POLICY_ID" 192.0.2.10 198.51.100.20 tcp/443 <hopIngressInterface> <hopEgressInterface>` | Both calls exit `0`; the hop verdict word equals the lookup verdict word (when the hop applies NAT, compare like with like: the tuple the hop evaluated, `packet_in` or the post-NAT tuple, is the lookup's tuple); an interface the inventory cannot bind reads `UNKNOWN`, never a guessed name | Design step 8; A0-ASA-1 to A0-ASA-5 | `trace_asa_hops_checked=<n>`, `trace_asa_verdict_vs_lookup=<MATCH\|MISMATCH\|NOT_EVALUABLE>`, `trace_asa_interface_binding=<PRESENT\|UNKNOWN>` | |
| T-F1 | The same with a tuple that crosses a FortiGate hop (`vendor` `FGT`), then `aiv policy-lookup "$FGT_POLICY_ID" 192.0.2.10 198.51.100.20 tcp/443 <hopIngressInterface> <hopEgressInterface>` | As T-A1; a zone placement is not evaluated (design section 11) and reads `UNKNOWN`, never allowed | Design step 8; A0-FG-1 to A0-FG-3, A0-FG-9 | `trace_fgt_hops_checked=<n>`, `trace_fgt_verdict_vs_lookup=<MATCH\|MISMATCH\|NOT_EVALUABLE>`, `trace_fgt_interface_binding=<PRESENT\|UNKNOWN>` | |
| T-H1 | `aiv topology` (`GET /api/v2/topology`) once, to record that a held snapshot exists and its age | Exit `0`; the age read is the age of the topology evidence, not of the policy snapshot | Design step 8 | `topology_held=<bool>` | |

### Step V — Viewer parity under `aiview` (masked)

Open the Policy screen as `aiview` (`?screen=policy`): the **Rules & Containers**, **Objects** and **Hygiene** tabs for
the ASA context and the FortiGate VDOM chosen above, plus the policy tree. Only counts and booleans are reported; the
PO looks, nothing is copied.

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| V1 | `aiv policy-tree` (`GET /api/v2/policy/tree`) and `aiv policy-show "$ASA_POLICY_ID" 0` and `aiv policy-show "$FGT_POLICY_ID" 0` against the screens | The tree and rule pages agree with what the screen shows; the screen header reads `Context:` for ASA and `VDOM:` for FortiGate; ASA and FortiGate rules, objects and tree render masked | Design step 4, Q4 | `viewer_rules_tab_vs_cli=<MATCH\|MISMATCH>`, `viewer_objects_tab_vs_cli=<MATCH\|MISMATCH>`, `viewer_tree_vs_cli=<MATCH\|MISMATCH>`, `viewer_unmasked_values_found=<n>` (target 0, counted by eye, the PO reports the count only) | |
| V2 | `aiv policy-domain-objects "$CONTAINER_ID" "$SOURCE_ID"` (`GET /api/v2/policy/domains/{id}/objects?source=...`) for the same ASA context, then for the FortiGate VDOM (`sourceId` and `containerId` from `policy-tree`) | Exit `0`; the object list agrees with the Objects tab; `UNKNOWN` or unreadable object states are shown as such, never as resolved | Design step 4, section 5.2 and 5.3 | `objects_tab_vs_cli=<MATCH\|MISMATCH>`, `objects_unknown_shown_as_ok=<bool>` (must be `false`) | |
| V3 | `aiv api GET "/api/v2/search?q=192.0.2.10&group=policy_objects"` (`GET /api/v2/search`), a partial address search over the stored snapshot scopes | Exit `0`; when a scope cannot be read the response carries `policy_objects_partial: true` and a count of unavailable scopes instead of hiding the scope; the screen shows the same | Address search over the local snapshot domains reports `partial=true` instead of hiding an unreadable scope (`docs/history/backlog/asa_fortigate_policy_model.md`, 2026-11-26 sync entry) | `search_partial_flag_present=<bool>`, `search_partial=<true\|false>`, `search_unavailable_scopes=<n>`, `search_scope_hidden_silently=<bool>` (must be `false`) | |

### Step X — Cleanup (always, even after a FAIL)

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| X01 | `aiv logout`; `rm -rf "$WORK"` | `logout` exits `0`; scratch directory removed; no raw or cached JSON and no sample file left behind | `AGENTS.md` Raw-evidence law | `logout_exit=0`, `scratch_removed=<bool>` | |

---

## 4. Verdict tokens and the closed reason vocabulary

**Product verdict** (`verdict` in the lookup answer): `ALLOWED`, `DENIED`, `UNKNOWN`, or `PARTIAL` (the queried range
splits into parts with different answers; `parts` lists them). `PARTIAL` is counted with `UNKNOWN`: it is not a
definite answer for the tuple.

**Owner's answer** (the PO's out-of-band step, one word per sample): `ALLOW`, `DENY`, `INCONCLUSIVE` (the owner's
own answer does not settle the tuple), or `NONE` (no answer obtained).

**Token per sample** (derived, exactly one):

| Product verdict | Owner's answer | Token |
|---|---|---|
| `ALLOWED` / `DENIED` | `ALLOW` / `DENY` and the same direction | `MATCH` |
| `ALLOWED` / `DENIED` | `ALLOW` / `DENY` and the opposite direction | `MISMATCH` (always a finding) |
| `ALLOWED` / `DENIED` | `INCONCLUSIVE` or `NONE` | `DEVICE_ANSWER_UNAVAILABLE` |
| `UNKNOWN` / `PARTIAL` | `INCONCLUSIVE` | `UNKNOWN_BOTH` |
| `UNKNOWN` / `PARTIAL` | `NONE` | `DEVICE_ANSWER_UNAVAILABLE` |
| `UNKNOWN` / `PARTIAL` | `ALLOW` / `DENY` | `-` (no token: counted as `UNKNOWN`, tallied in `*_unknown_device_definite`, never a `MATCH`; it is a candidate for the next parser or lookup change, not a defect by itself) |

**Counts per vendor:** `match` = samples with token `MATCH`; `mismatch` = token `MISMATCH`; `unknown` = every sample
whose product verdict is `UNKNOWN` or `PARTIAL` (tokens `UNKNOWN_BOTH`, `DEVICE_ANSWER_UNAVAILABLE` for a product
`UNKNOWN`, and `-`). **A product `UNKNOWN` is never counted as a `MATCH`**, whatever the owner's answer, and
`match + mismatch + unknown + (definite product answers with no owner answer) == samples`. The `UNKNOWN` reason code is
the product's own `lookupReason`, recorded exactly as one member of this closed list, never free text:

| Vendor | Closed `lookupReason` members expected on ASA or FortiGate lookups |
|---|---|
| ASA | `no rule matched; implicit deny of the access list`; `no access list applies; higher to lower security level is allowed by default`; `no access list applies; security-level default unproven`; `same security level; same-security-traffic inter-interface is not permitted`; `same interface; same-security-traffic intra-interface is not permitted`; `interface or security level not in the policy`; `access list results` |
| FortiGate | `no rule matched; implicit deny`; `no rule matched; zone intrazone setting not evaluated`; `FGT_VIP_PRIORITY_UNPROVEN`; `FGT_MATCH_VIP_UNPROVEN` |
| Both | `first matching rule`; `earlier rule could match`; `partial query coverage`; `analysis budget`; `snapshot collection incomplete`; `unsupported evaluation scope`; `zone or target required`; `target context unproven`; `application constraint`; `service-wide query requires partitioning`; `unsupported rule constraint` |

A `lookupReason` that is not a member of `PolicyLookup.REASONS` (`ui2/service/.../policy/PolicyLookup.java`) is recorded
as `reason_outside_closed_list` and is a finding. The Check Point and Palo Alto members of that set are not expected on
these two vendors; if one appears, record that fact as a count.

---

## 5. Result table for the build record

Paste one line per step in this exact shape; delete nothing and mark any skipped step `NOT_RUN` with its reason. No
hostname, address, serial, interface name, VDOM name, ACL or object name, rule body, user name or device output belongs
in this table.

| Step | PASS / FAIL / NOT_RUN | Evidence tokens |
|---|---|---|
| PR1-PR6 | `<PASS\|FAIL>` | `pr1_aiview=<bool>`, `pr2_asa_policies=<n>`, `pr2_fgt_policies=<n>`, `pr3_build_has_steps_4_to_8=<bool>`, `pr4_sample_units_chosen_by_po=<bool>`, `pr5_owner_answer_path=<bool>`, `pr6_held_topology=<bool>` |
| L-A1 | `<PASS\|FAIL\|NOT_RUN>` | `lookup_smoke_exit=0`, `lookup_reason_in_closed_list=<bool>` |
| L-A2, L-A3 | `<PASS\|FAIL\|NOT_RUN>` | `asa_samples=<n>`, `asa_match=<n>`, `asa_mismatch=<n>`, `asa_unknown=<n>`, `asa_unknown_both=<n>`, `asa_unknown_device_definite=<n>`, `asa_device_unavailable=<n>`, `asa_reason_outside_closed_list=<n>` |
| L-F1 | `<PASS\|FAIL\|NOT_RUN>` | `lookup_smoke_exit=0`, `lookup_reason_in_closed_list=<bool>` |
| L-F2, L-F3 | `<PASS\|FAIL\|NOT_RUN>` | `fgt_samples=<n>`, `fgt_match=<n>`, `fgt_mismatch=<n>`, `fgt_unknown=<n>`, `fgt_unknown_both=<n>`, `fgt_unknown_device_definite=<n>`, `fgt_device_unavailable=<n>`, `fgt_reason_outside_closed_list=<n>` |
| L-V1 | `<PASS\|FAIL\|NOT_RUN>` | `fgt_vip_samples=<n>`, `fgt_vip_match=<n>`, `fgt_vip_mismatch=<n>`, `fgt_vip_unknown=<n>`, `fgt_vip_device_unavailable=<n>` |
| H-A1 | `<PASS\|FAIL\|NOT_RUN>` | `asa_hygiene_counts_vs_tab=<MATCH\|MISMATCH>`, `asa_rules=<n>`, `asa_shadow_proven=<n>`, `asa_shadow_unknown=<n>`, `asa_budget_reached=<bool>`, `asa_class_shadowed=<n>`, `asa_class_conflict=<n>`, `asa_class_disabled=<n>`, `asa_class_unused=<n>`, `asa_class_expired=<n>`, `asa_class_broad=<n>`, `asa_class_unknown=<n>` |
| H-F1 | `<PASS\|FAIL\|NOT_RUN>` | `fgt_hygiene_counts_vs_tab=<MATCH\|MISMATCH>`, `fgt_rules=<n>`, `fgt_shadow_proven=<n>`, `fgt_shadow_unknown=<n>`, `fgt_budget_reached=<bool>`, `fgt_class_shadowed=<n>`, `fgt_class_conflict=<n>`, `fgt_class_disabled=<n>`, `fgt_class_unused=<n>`, `fgt_class_expired=<n>`, `fgt_class_broad=<n>`, `fgt_class_unknown=<n>` |
| H-P1 | `<PASS\|FAIL\|NOT_RUN>` | `ha_pairs=<n>`, `ha_same=<n>`, `ha_divergent=<n>`, `ha_unknown=<n>`, `ha_counted_once=<n>`, `ha_summary_rows_vs_stored_minus_counted_once=<MATCH\|MISMATCH\|NOT_EVALUABLE>` |
| H-U1 | `<PASS\|FAIL\|NOT_RUN>` | `acl_not_bound_count=<n>`, `acl_use_unknown_count=<n>`, `acl_not_bound_checked=<n>`, `acl_not_bound_owner_confirms_unreferenced=<n>`, `acl_not_bound_vs_owner=<MATCH\|MISMATCH\|NOT_EVALUABLE>`, `acl_unknown_counted_as_unbound=<bool>` |
| T-A1 | `<PASS\|FAIL\|NOT_RUN>` | `trace_asa_hops_checked=<n>`, `trace_asa_verdict_vs_lookup=<MATCH\|MISMATCH\|NOT_EVALUABLE>`, `trace_asa_interface_binding=<PRESENT\|UNKNOWN>` |
| T-F1 | `<PASS\|FAIL\|NOT_RUN>` | `trace_fgt_hops_checked=<n>`, `trace_fgt_verdict_vs_lookup=<MATCH\|MISMATCH\|NOT_EVALUABLE>`, `trace_fgt_interface_binding=<PRESENT\|UNKNOWN>` |
| T-H1 | `<PASS\|FAIL\|NOT_RUN>` | `topology_held=<bool>` |
| V1 | `<PASS\|FAIL\|NOT_RUN>` | `viewer_rules_tab_vs_cli=<MATCH\|MISMATCH>`, `viewer_objects_tab_vs_cli=<MATCH\|MISMATCH>`, `viewer_tree_vs_cli=<MATCH\|MISMATCH>`, `viewer_unmasked_values_found=<n>` |
| V2 | `<PASS\|FAIL\|NOT_RUN>` | `objects_tab_vs_cli=<MATCH\|MISMATCH>`, `objects_unknown_shown_as_ok=<bool>` |
| V3 | `<PASS\|FAIL\|NOT_RUN>` | `search_partial_flag_present=<bool>`, `search_partial=<true\|false>`, `search_unavailable_scopes=<n>`, `search_scope_hidden_silently=<bool>` |
| X01 | `<PASS\|FAIL>` | `logout_exit=0`, `scratch_removed=<bool>` |

Also record once, as plain facts: the deployed build or release tag, the date, `persona=aiview`, and
`executor=PO` (plus the owner of the out-of-band answers, by role, never by name).

**Rule.** Backlog item `asa_fortigate_policy_model` stays `AUTOMATED_VALIDATED` until the Product Owner reports the
run. Any `FAIL`, any `MISMATCH` (lookup, hygiene, pair counting, `ACL_NOT_BOUND`, Trace or viewer), any
`reason_outside_closed_list`, any `acl_unknown_counted_as_unbound=true`, any `viewer_unmasked_values_found` above `0`,
`search_scope_hidden_silently=true`, or any required step left `NOT_RUN` keeps the item at `AUTOMATED_VALIDATED` and
opens a follow-up item for that finding; a run with fewer than twenty samples per vendor says so and does not satisfy
design section 8. The separately recorded L-V samples never advance the two `@Disabled` pins. Automated validation
(including the CLI conformance test pinning this checklist) never replaces real-environment evidence, and recording the
result table is evidence for, never by itself, the Product Owner's decision to advance to `REAL_ENV_VALIDATED`.
