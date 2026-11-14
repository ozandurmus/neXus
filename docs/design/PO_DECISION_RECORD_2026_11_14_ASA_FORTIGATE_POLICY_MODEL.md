# PO decision record -- 2026-11-14: Cisco ASA and FortiGate policy model, Phase A answers

## Status

RATIFIED -- 2026-11-14, Product Owner answers relayed verbatim by the PO session, plus decisions the PO session took
itself (marked as such). Answers the questions of `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` section 10. This
record is the authority for the items below only; the design stays DRAFT for everything this record does not decide.

## Decisions

1. **Use of existing reads (Q1), PO:** "Evet, başla" ("Yes, start"). The stored output of the two reads already issued
   -- Cisco ASA `more system:running-config` (gate `asa_more_system_running_config`) and FortiGate `show` at the top
   level (gate `fgt_show_configuration`) -- may be parsed for the policy model, as a parse-scope extension: same
   command, session, timeout and frequency, **no new device command**. Source: the latest `show_configuration`
   configuration-plane artefact per enrolled gateway, decrypted and parsed in memory, discarded after parsing.
2. **Step 0 measurement (Q1), PO:** approved: one in-pod, counts-only measurement over those stored artefacts
   (design section 7). No device contact; only `[a-z0-9_.]+` fields leave the pod; no configuration text is read by a
   person or an agent.
3. **Vendor semantics (Q2), PO:** the A0 amendment
   `PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` becomes authority once its HIGH quotes have
   been re-verified on the source pages themselves; its UNKNOWNs stay UNKNOWN.
4. **ASA security-level default (Q3), PO session decision:** the uncertain default behaviour (A0-ASA-4b, A0-ASA-4c)
   stays UNKNOWN in verdicts; only the HIGH part (A0-ASA-4a, higher to lower permitted when no inbound and no global
   ACL applies) is used as a decided answer.
5. **Display (Q4), PO session decision:** ASA ACL and FortiGate policy contents are shown in the Policy area like
   Check Point and Palo Alto rules, masked for aiview. The 2026-09-27 Configuration allowlists are unchanged.
6. **HA pairs (Q5), PO session decision:** an HA pair counts once in hygiene and the zone matrix when both members'
   policy-relevant content is identical; a difference is flagged (`HA_POLICY_DIVERGENT`), never merged.
7. **Later reads (Q6), PO session decision:** hit counts (R1) and on-device corroboration (R2 `packet-tracer`, R3
   FortiGate full configuration) are deferred; none is approved by this record.
8. **Order (Q7), PO:** Cisco ASA first, then FortiGate.
9. **Validation targets (Q8), PO:** "ACI may send traffic anywhere; the next hop comes from IP, routes and contracts".
   There is no fixed target list; real-environment validation samples are picked from the measured fleet.
10. **Delivery routing, PO:** implementation lanes (parsers, mappers) go to Argon (Antigravity) through
    `scripts/argon_task.py` from a worktree at `origin/main`; the engineering session runs step 0 (pod access), and
    does the reviews, tests and integration.

## Not authorized by this record

Any new device command or API call; any read listed in design section 6 (R1-R3); a multiple-context ASA read; any
change to the Configuration-screen allowlists; any database write beyond the product's normal derived-on-read
snapshots.
