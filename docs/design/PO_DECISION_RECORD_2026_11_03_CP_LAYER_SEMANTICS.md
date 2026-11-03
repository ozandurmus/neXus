# PO decision record - 2026-11-03: Check Point section and inline-layer semantics for rule hygiene

**Status: RATIFIED - delegated decision (chat, 2026-11-03).** Scope: shadow/redundancy analysis on stored policy snapshots
only. No device command, no collection change.

## Context
Shadow analysis returned UNKNOWN "earlier sections not analysed" for about 12,650 Check Point rules because it scoped earlier
rules by section identity. The A0 vendor research (`PATH_ANALYSIS_VENDOR_SEMANTICS.md`, DRAFT, branch
`docs/path-analysis-a0-vendor-semantics-1103`) cites official Check Point documentation for two claims:

1. §2c (MEDIUM): rulebase sections are a visual division only.
2. §2b (HIGH): when a packet matches a rule inside an inline layer, no further rule matching is done; a matched "Apply Layer"
   parent rule is therefore terminal within its ordered layer.

The A0 document itself stays DRAFT; only these two claims are ratified here, for this use.

## PO answer (verbatim)
The engineer asked the PO to ratify the two claims. The PO answered: "Sorun varsa sor" ("Ask if there is a problem"). The
engineer reviewed the citations, found no problem, and ratified the two claims under that delegation.

## Decision
- Earlier rules are scoped by layer identity, not section identity. An inline child layer is analysed on its own; the parent
  layer's earlier-rule state resumes after it.
- An Apply Layer rule with a resolved match covers later rules of its parent layer. A rule it covers is REDUNDANT (MEDIUM,
  "this rule is never reached"), never CONFLICT, because the inline layer's own verdict is not known.
- Where layer identity cannot be established (missing or broken parent link, label anomalies, rule numbers not increasing),
  the result stays UNKNOWN "earlier sections not analysed". Rule enforcement order inside a layer is not documented and is
  not assumed beyond the existing increasing rule-number check.
- Install-on references are compared as opaque ids for exact set equality (identity law); this needs no vendor semantics.
  "Policy Targets" is not expanded into gateways (gap G7, path analysis A2).

## Revisit trigger
Real-environment evidence (A6) that contradicts either claim, or a Check Point version whose documentation differs.
