# PO decision record - 2026-11-14: gateway object bound (D-1) and the App-ID default-port read

**Status: RATIFIED - PO DECISION (relayed by the PO session, 2026-11-14).**

## Context
The 2026-11-13 collection-gap measurement (`scripts/measurement/policy_collection_gaps/README.md` on branch
`feat/policy-collection-gaps-1113`) attributed 13,945 of the 22,020 rules both analysers leave UNKNOWN on the 10
largest stored policies to Check Point gateway/cluster objects and 4,928 to Palo Alto application default ports.

## PO answers (verbatim, relayed)
- D-1: "Kabul" ("Accepted"): a Check Point gateway/cluster object matches at most its own addresses, used only to
  skip a rule, never as cover proof.
- App-ID read: "Onaylıyorum" ("I approve"), option A.

## Decision
1. **Gateway bound (D-1).** Parse the addresses from the existing `show-gateways-and-servers details-level full` read
   (gate `cp_policy_collect`, parse-scope extension, no new command) and use them only as an upper bound to pass an
   earlier rule over. Semantics: `PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_13.md` section G1.
2. **App-ID default ports.** One new CLASS_0 Panorama read, gate `pan_policy_predefined_applications` (V212):
   - literal `type=config&action=get&xpath=/config/predefined/application`;
   - one call per policy collection on the same Panorama session;
   - timeout 120 s, no retry, 64 MB cap;
   - keep only `default/port`, `default/ident-by-ip-protocol` and `default/ident-by-icmp(6)-type`;
   - any failure leaves the catalogue empty (application ports stay unknown) and the collection continues;
   - masked logs carry counts and an outcome code only.
   The legacy analysis uses the ports (amendment section D6); the sound analysis is unchanged.

## Cross-references
- Policy hygiene modes and resolution design, 2026-11-13 (side-session draft, not in the repository): items D-1, D-2, C1, C3
- `docs/design/CP_GATEWAY_OBJECT_BOUND_REVIEW_2026_11_05.md`
- Source: PAN-OS 10.2 XML API, "View Configuration Node Values for XPath" (official example of the full application list).
