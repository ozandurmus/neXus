# Brief for Fable: Policy Insights, rulebase reorganisation advisor (2026-11-24)

Status: BRIEF for a DRAFT design. You (Fable 5.2) write the design; the PO session reviews it; the PO decides.
You may read the repository only. Write English. Use masked names only (`FW-TANGO-04`, `CLS-ROMEO-01`, `DOM-ALPHA-01`,
`192.0.2.x`, `198.51.100.x`).

## 1. What the PO wants (read this first; an earlier summary missed it)

The PO's words (Turkish, 2026-11-24, translated), in order:

> "Think of a rule table with 100 rules, 90 % of them unused. Can we reorder inside the rulebase so those rules get
> hits? Then we could clean up the broad rules too."

> "For the logs: I can give monthly logs, the logs are in Splunk, say 6 months of logs for a policy table. Would that
> not allow a policy-based analysis and a refactor?"

> "By one policy I mean any policy package. The engine should use logs as support, but I think the analysis engine
> can do this to a certain level on its own; logs can be used for assisted verification. Let's design this as
> something like **Policy Insights**: in this policy this and that can be done, this part looks like this, if you want
> to do this work we can do it with X % accuracy, for X + 60-70 % logs must be provided -- it can bring suggestions
> like that. The goal is rulebase reorganisation."

So the feature is **Policy Insights**: an advisor over **any stored policy package** (a Check Point policy package or
access layer, a Palo Alto device-group or firewall rulebase, a Cisco ASA access-list set, a FortiGate VDOM policy).

1. **Policy-only analysis first, no logs needed.** The engine reads the stored rulebase and its objects and finds:
   - rules fully or partly shadowed by earlier rules;
   - redundant and duplicate rules, and merge candidates;
   - overly broad rules (any/any, wide subnets, many services);
   - reorder candidates that are safe by construction (same action, equal or stricter profile, no overlap with a
     rule of a different action in between);
   - disabled, expired or zero-hit rules, using the vendor's hit counters where stored, with their age and caveats;
   - unused objects.
2. **Each insight is a concrete suggestion with a stated confidence:**
   - what can be done;
   - why it looks that way;
   - what evidence it rests on;
   - the confidence achievable from the policy alone (e.g. "reorder: high, provable from the rulebase"; "remove broad
     rule R12: low from the policy alone").
3. **Logs are an optional booster.** For insights that the policy alone cannot prove (removing or narrowing a broad
   rule, confirming a zero-hit rule is truly unused, deriving narrow replacement rules), the insight says how far
   traffic logs would raise the confidence and what logs are needed. Example: "with 6 months of logs for this package,
   about +60-70 %". The PO's numbers are illustrative; the design defines how confidence is computed honestly, never as
   a made-up percentage. The bank can then upload an aggregated Splunk export; the engine uses it to verify and to
   derive replacement rules.
4. **The end product is a reorganised rulebase proposal for the package:**
   - new order;
   - narrowed or replaced broad rules;
   - merges;
   - removals;
   - an equivalence check: every flow seen in the logs (when given) keeps the same action and profile, and every flow
     that would change is listed. Without logs, the equivalence check is made on the rulebase itself (set semantics
     over the rule match space) where that is possible, and says where it is not.
5. **Read-only.** Nothing is written to devices (policy writes are not approved). The output is a reviewed proposal and
   export for the firewall team.

## 2. What the product already has (verify in the source; do not assume)

- Stored policy packages and rulebases per vendor, objects, and hit counts where the vendor reports them: the Policy
  screen (`?screen=policy`), Rules & Containers, Objects, Hygiene, Changes.
- Policy hygiene with shadowing verdicts and UNKNOWN reasons (`docs/design/POLICY_HYGIENE_W1_A_NOTES.md`, the hygiene
  service and its fleet summary); rule and object usage (`docs/design/RULE_OBJECT_USAGE_CONTRACT.md`). Say exactly what
  Policy Insights can reuse and what is missing.
- A flow-against-policy evaluator: Cutover impact (`ui2/service/.../policy/CutoverAssessment*.java`). It evaluates
  uploaded flows (CSV) against stored policy for Check Point, Palo Alto, Cisco ASA and FortiGate. It is the likely core
  of the log replay; state its limits (zones, applications, users, NAT, profiles, layers).
- Data rules:
  - agents never read raw customer data before anonymisation;
  - the aiview role sees a masked projection;
  - no raw retention without a contract (AGENTS.md "Raw-evidence law", `PRIVACY_AND_DATA_HANDLING.md`).

## 3. Design questions you must answer

1. **Insight catalogue.** Each insight type with:
   - its detection method from the policy alone;
   - the vendor semantics it depends on;
   - its confidence model without logs and with logs;
   - what a user does with it.
2. **Confidence model.** How confidence is computed and shown honestly. Inputs: provable from rule semantics, hit
   counter age and resets, HA members with separate counters, log coverage period and completeness, seasonal traffic.
   How "with logs you would reach Y" is estimated before logs exist.
3. **Logs as booster.**
   - The Splunk export: exact SPL per vendor, aggregated (e.g. `stats count earliest(_time) latest(_time) by src dest
     dest_port transport app rule action`).
   - Size estimate for six months.
   - Upload path, encrypted storage, retention and purge.
   - Mapping log lines to rules by rule uid or name, and handling rule changes during the period (the product stores
     revisions).
   - What logs cannot prove: unlogged denies, implicit rules, App-ID and user rules.
4. **Reorganisation proposal.**
   - Ordering algorithm.
   - Derivation of narrow rules from observed flows: aggregation strategy, reuse of existing objects and groups, a cap
     on rule-count growth.
   - Merge and removal rules.
   - Equivalence check with and without logs.
5. **Vendor specifics.**
   - Check Point: policy packages, ordered and inline layers, implied rules, sections.
   - Palo Alto: Panorama device groups, pre/post rules, zones, App-ID.
   - Cisco ASA: ACL per interface, object-groups.
   - FortiGate: policy id vs sequence, VDOMs.
6. **Screens.**
   - Policy Insights per package: insight list with confidence and "how to raise it".
   - Insight detail with evidence.
   - Proposal view: old vs new, side by side.
   - Replay result.
   - Exports.
   - Roles; aiview masked. Where it sits in the existing Policy area.
7. **Phasing.**
   - First slice: policy-only insights for one vendor, useful without any logs.
   - Then log intake and the confidence booster.
   - Then the full reorganisation proposal with equivalence.
   - Real-environment validation steps.
8. **Open PO questions**, each with options, pros and cons, and a recommendation first.

## 4. Output

Write `docs/design/POLICY_INSIGHTS_DESIGN_2026_11_24.md`, status line
`Status: DRAFT (Fable 5.2, 2026-11-24) - not implementation authority until the PO freezes it.`

Separate verified repository facts (with file references) from proposals, and mark UNKNOWN anything the source does
not establish. Keep it as long as it needs to be and no longer.
