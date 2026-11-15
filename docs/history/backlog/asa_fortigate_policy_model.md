# ASA and FortiGate policy model for lookup and hygiene

status: in_progress · target: ACI decision 2026-11-14

2026-11-14: policy lookup covers Check Point and Palo Alto only; ASA/FortiGate targets are NOT_EVALUABLE. Lane asa-forti-policy started after the PO's ACI decision. Configuration artefacts for both vendors already exist (ASA 313, FortiGate 228 live).

2026-11-14: steps 0-2 on main (shapes, ASA parser, mapper); output shaping in batch 2, wiring in batch 4.

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Built beyond the parsers: ASA stored policy shown as local firewall policy (step 2b), ASA evaluation in normalization/lookup/hygiene (steps 5-6), FortiGate VDOM policies derived and evaluated (steps 2b, 5-6), ASA Trace (step 8). Open: FortiGate Trace wiring, live validation.
