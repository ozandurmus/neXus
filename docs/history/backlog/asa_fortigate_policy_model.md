# ASA and FortiGate policy model for lookup and hygiene

status: in_progress · target: ACI decision 2026-11-14

2026-11-14: policy lookup covers Check Point and Palo Alto only; ASA/FortiGate targets are NOT_EVALUABLE. Lane asa-forti-policy started after the PO's ACI decision. Configuration artefacts for both vendors already exist (ASA 313, FortiGate 228 live).

2026-11-14: steps 0-2 on main (shapes, ASA parser, mapper); output shaping in batch 2, wiring in batch 4.

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Built beyond the parsers: ASA stored policy shown as local firewall policy (step 2b), ASA evaluation in normalization/lookup/hygiene (steps 5-6), FortiGate VDOM policies derived and evaluated (steps 2b, 5-6), ASA Trace (step 8). Open: FortiGate Trace wiring, live validation.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): FortiGate Trace wiring is now on main (commit 'Trace: FortiGate units in topology, policy binding, hop evaluation and path engine (step 8)'), so every built step is live; also ASA nameif map for Trace. Remaining: live validation of ASA and FortiGate lookup, hygiene and Trace against real stored policies.

2026-11-23 (lane q-398): HA_POLICY_DIVERGENT (record decision 6, design 5.1) shipped as a read-only comparison: `HaPolicyPairing` hashes each member snapshot canonically (HAPOL1, per-member ids/times/hits excluded) and `GET /api/v2/policy/ha-pairs` (policy_read, masked for aiview) reports SAME / HA_POLICY_DIVERGENT / UNKNOWN per ASA and FortiGate pair and context, only for pairs whose `ha_pair_identity` is MATCH. Nothing is merged yet: "count once" in hygiene and the zone matrix is the follow-up lane; live validation against stored pairs is open.
