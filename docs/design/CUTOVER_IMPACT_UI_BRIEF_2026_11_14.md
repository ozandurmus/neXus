# Pre-cutover impact analysis screen - UI brief

## Status

DRAFT - 2026-11-14. UI brief for the Carbon (Antigravity) lane that builds the step 1 screen of
`ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md`; the backend is on branch `feat/cutover-assessment-1114`. It adds no
backend route and decides no product semantics beyond that design and `PO_DECISION_RECORD_2026_11_14_ACI_SEGMENTATION_SUPPORT.md`.

Design:
`docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md` section 4 (+ section 0 for step-1 differences), PO record
`PO_DECISION_RECORD_2026_11_14_ACI_SEGMENTATION_SUPPORT.md`.

## Where
Menu: Policy and changes > "Cutover impact" (next to Rule object usage). Gate: `cutover_assessment_read`
(security_admin, compliance_admin, onboarding_admin, viewer, aiview masked). Write controls only when `canManage` is true.

## API (all JSON; aiview responses are already masked server-side)
- `GET /api/v2/cutover/segments` -> `{segments:[{segmentId,name,revision,blocks[],peerBlocks[],createdAt,updatedAt,...}], canManage}`
- `POST /api/v2/cutover/segments` `{name, blocks[], peerBlocks[]}`; `PUT .../segments/{id}` adds `revision` (409 on conflict); `DELETE .../segments/{id}`
- `GET /api/v2/cutover/segments/{id}` -> segment + `assessments:[{assessmentId,createdAt,readiness,status,headline,counts,targetDeviceId,targetPolicyId,...}]`, `canManage`
- `POST /api/v2/cutover/segments/{id}/assessments` multipart: field `spec` FIRST, then one `file` part per mapping.
  spec = `{targetOverride?: {deviceId, policyId?, context?, segmentZone?, peerZone?}, oldPolicyId?, oldDeviceId?, oldContext?, mappings:[{source,destination,port,protocol,time?,count?,action?,sourceZone?,destinationZone?}...]}`
  DEFAULT (no `targetOverride`): every flow's target firewall comes from its computed path (Trace engine over the held
  topology). 409 `TOPOLOGY_NOT_BUILT` when no topology is held (link to Trace's rebuild). `targetOverride` is an
  explicit operator choice and is labelled `OPERATOR_OVERRIDE` everywhere (PO 2026-11-14: "ACI may send traffic
  anywhere; the next hop comes from IP, routes and contracts").
  (column mapping UI as in Rule object usage, one per file, max 8 files, 100 MB each). Returns `{assessmentId, readiness, status}`.
- `GET /api/v2/cutover/assessments/{id}` -> summary: `readiness, status, headline, targetSource (PATH|OPERATOR_OVERRIDE), topologySnapshotId?, counts{tuples,flows,blockingTuples,openTuples,alreadyDroppedTuples,WOULD_DROP,NOT_DECIDED,NOT_EVALUABLE,WOULD_ALLOW,missingRuleGroups}, coverage[{code,count?,periodStart?,periodEnd?}], uploads[], targetVendor, segmentZone, peerZone, targetCollectedAt, stale, staleReasons[]`
- `GET /api/v2/cutover/assessments/{id}/flows?kind=FLOW|MISSING_RULE&decision=WOULD_DROP|NOT_DECIDED|NOT_EVALUABLE|WOULD_ALLOW&page=N` (100 per page) -> `{total, items[]}`
  FLOW item: `direction, flowSource, flowDestination, protocol, port, flowCount, firstSeen, lastSeen, decision, decisionReason, targetSource, pathFirewalls[{deviceId,vendor,hopVerdict,decidingRule?}]?, decidingRule{ruleId,number,name,sectionName,action}?, flags[], conditionalRule?, droppedConstraints[]?, oldPathDecision?, oldPathRule?`
  MISSING_RULE item: `direction, flowDestination, protocol, port, sourceCount, flowSources[] | sourceRange, flowCount, firstSeen, lastSeen, decisionCounts[], oldPathRule?`
- `DELETE /api/v2/cutover/assessments/{id}`

## Screen
1. Segment list (name, block count, last verdict and its age). Create/edit form: name, blocks, peer blocks (one CIDR
   per line; server refuses non-canonical blocks, show its message verbatim).
2. Segment page: "New assessment" panel. Default: "Target firewalls: from each flow's computed path" (no picker), with
   the note that the fabric redirect (contracts, PBR) is not modelled yet, so paths are today's routed paths.
   An "Override target" switch, off by default, opens: target firewall (device picker), its stored policy (picker
   filtered to policies naming that device; ASA/FortiGate: no policy picker, note "policy not collected yet: every
   flow will be NOT_EVALUABLE"), Palo Alto: segment-side zone and peer-side zone (required, explain why). Then the
   optional old-path policy and the files with per-file column mapping.
3. Assessment page: verdict line = `headline` + readiness chip + a "Target: computed path" or "Target: OPERATOR
   OVERRIDE" label (always visible), then the COVERAGE LIST directly under it (never
   collapsed, never empty). Tabs: Would drop | Not decided (group by `decisionReason`) | Missing rules | Would allow
   | Not evaluable. STALE banner when `stale` (show `staleReasons`).

## Hard rules (acceptance criteria ACI-1, ACI-6)
- No green state anywhere, no word "safe", "ready", "pass", "healthy", "symmetric" in any label or chip. Best verdict
  `NO_BLOCKERS_IN_EVALUATED_FLOWS` is a neutral chip, with the coverage list right under it.
- Use the server's `headline` text as-is. Never compute a verdict in the browser.
- aiview: render only what the API returns (masked); no raw toggles; mask chip from the `X-Nexus-Masked` header.
- Fixtures and screenshots: documentation ranges and masked names only (192.0.2.x, 198.51.100.x, FW-TANGO-04).
- `CONDITIONAL_RULE` flag: show "rule X could match only if <droppedConstraints> hold; not decided".
- `ALREADY_DROPPED_TODAY`: show it, grey, outside the blocker count.
- Per flow in routed mode: the firewalls on the path (`pathFirewalls`, masked device names via the device list) with
  each hop's verdict, and a link to Trace for the same tuple.

CLI parity exists (`cutover-*` commands), so the screen adds no new backend route.
