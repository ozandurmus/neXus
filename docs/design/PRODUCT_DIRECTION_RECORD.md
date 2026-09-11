<!-- Placed under docs/design/ by GOV.PO.1 section 10 step 0 (2026-09-07). Authority: docs/design level 6, per GOV_PO_ROLE_MIGRATION.md section 4.1. Not ratified until an explicitly authorized governance PR merge records RATIFIED on the status line. -->
**Status: RATIFIED — PRODUCT OWNER, 2026-09-08, PR #112, decision relay ozandurmus/nexus-agent-relay#7 (`RELAY_DECISION ratification`).** Extracted from the previous Product Owner assistant on 2026-09-07 with provenance tags; every `[PO-DIRECTION]` item was resolved by the Product Owner's written "DIRECTION RECORD RATIFICATION" directive of 2026-09-08 (§0). `[ASSISTANT]` items remain labelled hypotheses until repository evidence promotes them (`GOV_PO_ROLE_MIGRATION.md` §4.1). Authority level 6 (`docs/design`); never implementation authority; a contradiction with `project/*.json` or a FROZEN contract is reported, not reconciled.

# neXus / SecurityExpert Product Direction Record

### §0 Ratification decisions (Product Owner, 2026-09-08)

Each `[PO-DIRECTION]` item below carries `→ ratified DR-n`, pointing here.
Boundaries are part of the decision.

| id | Decision | Boundary |
| --- | --- | --- |
| DR-1 | **Tufin path authority — ACCEPT.** neXus consumes Tufin-provided paths; it does not build an independent topology/path engine. Recorded durably as `project/roadmap.json` `open_decisions` id `tufin_path_authority`. | Missing or insufficient Tufin evidence stays explicit (`UNKNOWN`/`INSUFFICIENT_EVIDENCE`); no fallback authority is invented. |
| DR-2 | **Product objective — ACCEPT.** Persistent registry, database metadata plane, interactive operator experience. Compliance, backup and failover may use that experience as their contracts and maturity gates permit. | Grants no device-write or production authorization. Sequencing among M10 / compliance / backup / failover belongs to the first `PLAN` episode. |
| DR-3 | **Bounded prompts and proportionate process — ACCEPT.** Routine work stays concise; settled architecture is not reopened without new evidence. | Existing lifecycle, evidence and required-validation obligations remain applicable. |
| DR-4 | **Active movement and integration ownership — ACCEPT WITH CHANGE.** One declared primary movement and one integration owner. | Parallel work is allowed where existing governance permits it and independence is demonstrated; separate worktrees alone do not require a new contract. |
| DR-5 | **Member-skew policy — VERIFIED, converted to `[REPO]`.** Canonical source `D-F2` in `OP_2_1B_CP_PILOT_READINESS_POLICY_AMENDMENT.md`. | Not conflated with `D-F3` (flap) or `D-F1` (intent max age). |
| DR-6 | **Production timing — ACCEPT WITH CLARIFICATION.** No production migration is authorized now; deployment-specific hardening stays on its designated track. | Security defects affecting currently used behavior are assessed when discovered; "hardening later" is not a blanket deferral. |
| DR-7 | **Historical council tooling direction — SUPERSEDED** by `GOV_PO_ROLE_MIGRATION.md` (council as a repository skill with fresh-context seats, invoked only from a PO episode). | Historical records and self-critique disclosures are preserved; not retroactively described as independent council work. |
| DR-8 | **Environment and local secret handling — ACCEPT.** Use the validated interpreter appropriate to the actual environment; do not assume `py` in every shell or bootstrap another runtime without need. | Secrets stay local and never enter chat, relay or repository metadata. |
| DR-9a | **Original feature brief — recorded** as one discoverable backlog umbrella (`policy_rule_hygiene_and_path_placement_brief`) with its distinct capabilities retained. | Intent only; approves no implementation and no bulk-disable operation. |
| DR-9b | **Turkish stakeholder explanation — ACCEPT** as a separate artifact from English engineering artifacts (`nexus-po` skill §5). | Never inside or around an exact session packet. |
| DR-9c | **Deferred human validation — ACCEPT:** tracked durably (backlog/roadmap), independent authorized work continues. | The deferred behavior is never marked validated or complete. |
| DR-9d | **Next authorized prompt without re-confirmation — ACCEPT** (`nexus-po` skill §5). | No inferred approval of an unaccepted movement; no invented work. |
| DR-9e | **Subscription plans establish no engineering authority — ACCEPT.** | — |

Ratification mechanics: `GOV_PO_ROLE_MIGRATION.md` §4.1 / D14 — the
Product Owner's written authorization of 2026-09-08 covers exactly these
changes; the authorized merge of the governance PR named in the status line
is the ratification.

This is a one-time provenance-preserving extraction. It is evidence for human Product Owner review, not a new authority, contract, roadmap, or approval. Repository statements are referenced rather than silently re-ratified; chat-derived statements remain subordinate until the human Product Owner records them durably. [ASSISTANT confidence: high]

### §1 Product thesis and non-negotiables

- neXus / SecurityExpert is a network-security state, evidence, assurance, recovery-readiness, and ultimately controlled-operations platform—not merely an inventory script. See `PROJECT_VISION.md`. The reason for the staged thesis is that write capability is safe only after observation, verification, traceability, and recovery evidence are trustworthy. [REPO [PROJECT_VISION.md — Product identity](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md)]

- The product progression is `SEE → VERIFY → TRACE → RECOVER → OPERATE`. Schedule pressure must not invert that order by making mutation capability the mechanism used to discover whether the evidence model is correct. See `PROJECT_VISION.md` and `AGENTS.md`. [REPO [AGENTS.md — Engineering laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#engineering-laws)]

- The architecture is vendor-neutral at product-concept boundaries while retaining vendor-native semantics and provenance. A false common model is worse than an explicit vendor difference or `UNSUPPORTED`. See `PROJECT_VISION.md`. [REPO [PROJECT_VISION.md — Multi-vendor direction](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#multi-vendor-direction)]

- Inventory, Configuration, and Alignment are distinct planes. Shared input data does not make runtime state, configured intent, and expected-versus-actual comparison interchangeable. See `AGENTS.md` and `PROJECT_VISION.md`. [REPO [AGENTS.md — Engineering laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#engineering-laws)]

- Evidence identity is not operational identity; management-plane observation is not direct-device truth; one member's peer report is not independent peer corroboration; presentation identity is not security identity; pair existence is not pair health; readiness is not authorization. These separations are non-negotiable because collapsing any one of them can turn an uncertain join or observation into permission to act. See `AGENTS.md`. [REPO [AGENTS.md — Evidence laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#evidence-laws)]

- Identifiers are opaque. Do not coerce, trim, zero-strip, case-normalize, or infer equivalence merely to make two observations match. `UNKNOWN`, `MISMATCH`, and `NOT_EVALUABLE` are valid product outcomes. See `AGENTS.md`. [REPO [AGENTS.md — Identity law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#identity-law--identifiers-are-opaque)]

- Evidence outranks assumptions. A successful command, present field, plausible label, or familiar vendor term does not establish meaning. Safety-critical vendor semantics require repository evidence, bounded real-environment evidence, and official vendor documentation where applicable. See `AGENTS.md`. [REPO [AGENTS.md — Vendor semantics law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#vendor-semantics-law)]

- Automated validation is never silently promoted to real-environment validation. A network-facing build that requires real evidence cannot become `DONE` from fixtures alone. See `AGENTS.md`. [REPO [AGENTS.md — Mandatory build lifecycle](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#mandatory-build-lifecycle)]

- Secrets and raw local identities must not enter browser payloads, repository metadata, Git history, relay packets, ordinary chat, screenshots for sharing, or support artifacts. Local comparison should emit relationships such as `MATCH`, `MISMATCH`, `MISSING`, `NOT_EVALUABLE`, or `AMBIGUOUS`, not values. See `AGENTS.md`. [REPO [AGENTS.md — Sensitive identity reporting law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#sensitive-identity-reporting-law)]

- Browser/UI code projects typed server conclusions; it does not compute identity, topology, readiness, authorization, or vendor semantics. This protects both security authority and report/console parity. See `AGENTS.md`, `PROJECT_VISION.md`, and the active frozen UI contracts. [REPO [AGENTS.md — Architectural invariants](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#architectural-invariants-test-enforced-not-merely-current)]

- A new diagnostic convenience must not create a second credential or transport path when the controlled application path can answer the question. Reuse the authenticated transport and derive only bounded, sanitized evidence. See `AGENTS.md`. [REPO [AGENTS.md — Diagnostic-path law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#diagnostic-path-law)]

- Raw vendor responses are not retained merely to ease debugging. Parse minimum semantics, retain safe classes/relationships/tokens when authorized, and discard the raw response unless a specific evidence/forensics contract governs retention. See `AGENTS.md`. [REPO [AGENTS.md — Raw-evidence law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#raw-evidence-law)]

- The repository must be sufficient to cold-start every assistant. Chat memory, handover prose, model identity, and relay locators are never product authority. See `AGENTS.md` and `AI_START_HERE.md`. [REPO [AGENTS.md — Authority hierarchy](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#authority-hierarchy)]

- Tufin supplies path discovery for future path-based policy placement. neXus must consume the returned device path and must not build an independent topology/path engine. This was repeatedly stated in Product Owner chat; I did not verify an equivalent durable repository statement. [PO-DIRECTION 2026-08–09, ChatGPT PO chat] → ratified DR-1

- The product goal is a usable control plane: a persistent device identity/registry layer, a database-backed metadata plane, and an interactive operator experience that can support compliance, backup/recovery, and failover workflows without making the UI an authority. [PO-DIRECTION 2026-09-05–07, ChatGPT PO chat] → ratified DR-2

### §2 Decision record

#### Governance and delivery decisions

1. **`gov_relay_1_protocol` — 2026-09-07.** Question: governed GitHub relay versus ad hoc phrases/comments. Options: locator plus exact protocol-v2 packets and closed intermediate markers; or unconstrained chat/comments. Chosen: `RELAY_READY` is locator-only, issue body is one validated `SESSION_START`, final engineering comment is one validated `SESSION_CLOSE`, material questions use `RELAY_QUESTION`, and only the PO resolves them through `RELAY_DECISION`. Rejected: `RELAY_START`, `RELAY_END`, invented markers, or treating a locator as authority, because they cannot be structurally validated and caused M9 ownership/scope confusion. Status: **decided**; see `project/roadmap.json` and the frozen relay contract. [REPO [project/roadmap.json — gov_relay_1_protocol](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

2. **`GOV.SESSION.1A` — 2026-09-07.** Question: split/minimal session marker versus one complete symmetric transfer packet. Options: protocol-v1-shaped marker with narrative elsewhere; or protocol-v2 packet whose nested report is complete. Chosen: v2, exact schema, no narrative outside sentinels across a tool/session boundary. Rejected: pointer-only or partial packets because they lose the facts needed for independent validation. Status: **decided/FROZEN**; see `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md`. [REPO [docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md](https://github.com/ozandurmus/neXus/blob/main/docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md)]

3. **Git authorization/execution — 2026-09-07.** Question: must the human physically operate every PR/push/merge, or may an authorized agent execute the named action? Chosen: the human PO controls authorization; an agent may execute and verify the explicitly authorized Git action without asking again. Rejected: equating human control with manual clicking, because it created repeated blocking despite an explicit decision. Status: **decided**; see `AGENTS.md`. [REPO [AGENTS.md — Git authority and execution law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#git-authority-and-execution-law)]

4. **Engineering output language — 2026-09 period.** Question: follow conversation language or use one engineering language. Chosen: Turkish may be used with the human; repository artifacts, prompts, packets, PRs, commits, and handovers are English. Rejected: Turkish engineering preambles because they reduce portability and created inconsistent artifacts. Status: **decided**; see `AGENTS.md`. [REPO [AGENTS.md — Engineering-output language law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#engineering-output-language-law)]

5. **Implementation prompt scale — 2026-09 period.** Question: architecture-scale prompts for every movement versus bounded prompts. Chosen: one coherent objective, a few invariants, focused tests, fast CI; long contracts only for genuine durable authority changes. Rejected: mega-prompts for routine work because they caused long loops, reopened settled design, and burned context/credits. Status: **decided operating direction**, partly reflected in `AGENTS.md` and `docs/AI_DEVELOPMENT_PROTOCOL.md`; the causal history came from PO chat. [PO-DIRECTION 2026-09-05–07, ChatGPT PO chat] → ratified DR-3

6. **Full-regression topology — 2026-09-06.** Question: automatic full suite on every PR/push versus local risk-triggered evidence and on-demand cloud dispatch. Options actually tried: automatic PR, automatic push-to-main, then workflow-dispatch-only. Chosen: PR runs fast `validate`; full regression runs locally when blast-radius triggers demand it; GitHub-hosted full regression is exceptional `workflow_dispatch` only. Rejected: automatic triggers because of cost/latency and empty/duplicative runs; the parallel command itself remains preferred. Status: **decided**; see `docs/AI_DEVELOPMENT_PROTOCOL.md`. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — CI validation policy](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#ci-validation-policy-canonical--devtest1-final-topology-2026-09-06)]

7. **One active movement / one integration owner — 2026-09 period.** Question: continuously split development among multiple chats versus preserve one owner per coupled movement. Chosen for the M-series/M9 period: one implementation owner and one active movement; independent reviewers may work in parallel, but coupled writers do not share a branch. Rejected: ad hoc parallel editing because state files, frozen contracts, and integration ownership collide. Status: **current operating direction, not verified as a durable repository decision**. [PO-DIRECTION 2026-09-05–07, ChatGPT PO chat] → ratified DR-4

#### Operator Console decisions

8. **`C-D1` — 2026-09-01.** Question: FastAPI/uvicorn optional console dependency, stdlib server, or no console. Chosen: optional FastAPI/uvicorn extra. Rejected: stdlib-only because boundary request validation is a security control; rejected no-console because the operator surface is a product requirement. Status: **decided**. [REPO [project/roadmap.json — C-D1](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

9. **`C-D2` — 2026-09-01.** Question: fragment-delivered cookieless launch token, cookie session, or loopback-only unauthenticated. Chosen: cookieless per-launch bearer token in the URL fragment. Rejected: cookies because ambient credentials reintroduce CSRF concerns; loopback-only because locality is not authentication. Status: **decided**. [REPO [project/roadmap.json — C-D2](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

10. **`C-D3` — 2026-09-01.** Question: `console` provenance or reuse `manual`. Chosen: distinct `console`. Rejected: reuse because it destroys audit distinction between CLI and UI-triggered runs. Status: **decided**. [REPO [project/roadmap.json — C-D3](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

11. **`C-D4` — open.** Question: one target, N targets, or fleet selection per operational-write request. Current recommendation: one target for the pilot. No option ratified; N/fleet remain unchosen because pilot safety should be structural rather than throughput-oriented. Status: **open**. [REPO [project/roadmap.json — C-D4](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

12. **`C-D5` — open.** Question: console exposure on a server before OIDC/RBAC. Current recommendation: no; local loopback only. Server exposure is not ratified because reverse-proxy placement is not an authorization boundary. Status: **open**, aligned with the separately open `pcp_server_enrollment_exposure`. [REPO [project/roadmap.json — C-D5](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

13. **`C-D6` — open.** Question: mandatory operator reason and retention for writes. Recommendation: bounded, redaction-filtered, support-bundle-excluded reason. Optional/no reason remain unchosen because timestamps alone are not an adequate audit explanation. Status: **open**. [REPO [project/roadmap.json — C-D6](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

14. **`C-D7` — open.** Question: browser editing of scheduler policy. Recommendation: read-only in this track. Editing remains unchosen because it becomes a privilege path into unattended device contact. Status: **open**. [REPO [project/roadmap.json — C-D7](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

15. **`C-D8` — open.** Question: dedicated `CON.x` track versus folding into engineering or trace work. Recommendation: keep `CON.x`, because it is an operator-visible delivery surface across themes. No final choice recorded. Status: **open**. [REPO [project/roadmap.json — C-D8](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

#### Failover/readiness decisions

16. **`op_track_id` — open.** Question: dedicated `OP.x` OPERATE track or fold into `1.x` after GOVERN. Recommendation: keep dedicated because the mutation gate is materially different from platform governance. Status: **open**. [REPO [project/roadmap.json — op_track_id](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

17. **`op_four_eyes` — open.** Question: mandatory second approver versus configurable/default-on. Recommendation: configurable/default-on; architecture owns the approval-policy seam, deployment policy chooses quorum. Mandatory-v1 remains unchosen because it can block a legitimate emergency. Status: **open**, not an OP.2.0 freeze blocker. [REPO [project/roadmap.json — op_four_eyes](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

18. **`op_degraded_verdict` — open.** Question: expose `DEGRADED_PROCEED_WITH_RISK` in v1 versus only SAFE/UNSAFE/INSUFFICIENT. Recommendation: keep DEGRADED structurally unreachable until real-field calibration. Status: **open**, owed before OP.1 planning semantics depend on it. [REPO [project/roadmap.json — op_degraded_verdict](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

19. **`op_emergency_evac` — open.** Question: emergency evacuation in OP.2 or later OP.3. Recommendation: defer; no emergency path may bypass authorization, fresh preflight, confirmation, or lock. Status: **open**, not an OP.2.0 freeze blocker. [REPO [project/roadmap.json — op_emergency_evac](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

20. **`op_aa_vsls_scope` — open.** Question: PAN A/A and CP VSLS mutation in initial OP.2 or later. Recommendation: defer execution adapters while preserving read-only assessment. Rejected for initial scope because operational semantics differ and must not leak into the first ClusterXL adapter. Status: **open/deferred in practice**. [REPO [project/roadmap.json — op_aa_vsls_scope](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

21. **`op_continuity_tolerance` — open.** Question: fixed or per-run-tunable post-action continuity threshold. Recommendation: fixed conservative defaults only after calibration; until then observations are recorded but not verdict-bearing. Rejected now: invented percentages, because no real calibration supports them. Status: **open**. [REPO [project/roadmap.json — op_continuity_tolerance](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

22. **`D-V1` — open.** Question: exhaustive PAN connection-field vocabulary/missing-field meaning. Options: official documentation or bounded real measurement. Chosen minimum: `up` may support healthy; absent/other is fail-closed. Rejected: generic product-memory completion. Status: **open residual real-env parser validation; not a freeze blocker**. [REPO [project/roadmap.json — D-V1](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

23. **`D-V2` — open.** Question: exhaustive PAN sync/compatibility/preemption vocabulary and error binding. Options: official documentation or bounded real measurement. Chosen minimum fail-closed binding; rejected invented exhaustive semantics. Status: **open residual; not a freeze blocker**. [REPO [project/roadmap.json — D-V2](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

24. **`D-V3a` — open.** Question: PAN serial-field semantics in HA state. Options: official-source confirmation routes. No chosen semantic. Rejected: SDK-name correspondence or generic knowledge. Status: **open; blocks successor identity model and PAN CLASS 2, not read-only freeze**. [REPO [project/roadmap.json — D-V3a](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

25. **`D-V3b` — open.** Question: reciprocal real PAN pair correspondence after conflicting observations. Option: bounded investigation. No resolution chosen. Rejected: coercion/normalization to force a match and side-effect closure in unrelated work. Status: **open/hardware-dependent; B2 not established**. [REPO [project/roadmap.json — D-V3b](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

26. **`D-V4` — 2026-09-03.** Question: PAN `running-sync` location. Chosen from official source: group-scope state source. Rejected: further speculation once official evidence closed the question. Status: **decided/CLOSED_BY_DOCS**. [REPO [project/roadmap.json — D-V4](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

27. **`D-V5a` — open.** Question: exact CP failover-statistics flags and shell/schema parity. Options: official mirror, human-fetched official page, or command-gate research. Minimum parser is frozen; exact command approval is not. Status: **open; required before command-gate approval, not architecture freeze**. [REPO [project/roadmap.json — D-V5a](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

28. **`D-V5b` — 2026-09-03.** Question: CP VSX per-VS applicability of failover statistics. Chosen: not load-bearing; frozen battery uses physical/VS0 level only. Rejected: creating a per-VS requirement with no consumer. Status: **decided**. [REPO [project/roadmap.json — D-V5b](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

29. **`D-V6` — open.** Question: exact CP pnote command differentiation and state fields. Chosen minimum: complete pnote enumeration and fail-closed problem/no-problem interpretation; exact precision remains. Rejected: earlier “problem-filtered output” hypothesis. Status: **open, non-freeze-blocking**. [REPO [project/roadmap.json — D-V6](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

30. **`D-V7a` — 2026-09-03.** Question: CP recovery/preemption behavior. Chosen: official distinction between maintaining the active member and switching to higher priority. Rejected: inferring behavior from cluster-mode labels. Status: **decided/CLOSED_BY_DOCS**. [REPO [project/roadmap.json — D-V7a](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

31. **`D-V7b` — open.** Question: machine-readable CP configured-recovery surface. No surface was invented. The readiness-role decision later made this exact missing fact advisory-exempt for CP, but the vendor question remains open and is still required before CLASS 2. Status: **open vendor fact; readiness role decided separately**. [REPO [project/roadmap.json — D-V7b](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

32. **`D-V8` — open.** Question: optional CP hotfix-parity command. Options: official docs or bounded real measurement. No command/meaning chosen; rejected generic knowledge. Status: **open/non-blocking**. [REPO [project/roadmap.json — D-V8](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

33. **`D-V9a` — 2026-09-03.** Question: CP VSX documented caveat. Chosen safe interpretation: contradictory non-VS0 evidence becomes `UNKNOWN/RELATIONSHIP_INCONSISTENT`, never known-bad or an action input. Rejected: allowing uncertain per-VS evidence to drive mutation. Status: **decided/partial but sufficient**. [REPO [project/roadmap.json — D-V9a](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

34. **`D-V9b` — open.** Question: caveat applicability to the actual estate version. Option: bounded real measurement. No answer chosen; the frozen safe interpretation holds either way. Status: **open/informational**. [REPO [project/roadmap.json — D-V9b](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

35. **`D-F3` — 2026-09-05.** Question: numeric flap/failover-frequency threshold. Options: fixed, bounded tunable, or no threshold/advisory-exempt. Chosen: no invented threshold; cumulative counters without a window remain visible `INSUFFICIENT_EVIDENCE` but no longer independently block readiness. Rejected: fixed/tunable numbers unsupported by evidence. Status: **decided**. [REPO [project/roadmap.json — D-F3](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

36. **Member-skew policy — 2026-09-05 period.** Question: invent a numeric skew threshold or retain the observed difference without making it a standalone blocker. Chosen: no numeric threshold; coherent same-run evidence may report nonzero skew without that fact alone blocking. Rejected: fabricated thresholds. Status: **decided** — verified 2026-09-08 (DR-5): the canonical decision is `D-F2` in `docs/history/phase/OP_2_1B_CP_PILOT_READINESS_POLICY_AMENDMENT.md` ("no threshold, ever"; skew recorded for disclosure only; the roll-up's unresolved-policy gate no longer lists it). Distinct from `D-F3` (flap threshold) and `D-F1` (configuration-intent max age). `project/roadmap.json` `open_decisions` carries no separate `D-F2` row; it is referenced inside `D-F3`. [REPO docs/history/phase/OP_2_1B_CP_PILOT_READINESS_POLICY_AMENDMENT.md — D-F2]

37. **`op_reversal_model` — 2026-09-04.** Question: automatic rollback versus reversal as a new typed action. Chosen: reversal/failback is a new CLASS 2 action with new authorization, preflight, confirmation, lock, one submission, verification, and audit. Rejected: automatic rollback because it issues a second mutation precisely when state is uncertain. Status: **decided/FROZEN**. [REPO [project/roadmap.json — op_reversal_model](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

38. **`op_outcome_unknown_recovery` — 2026-09-04.** Question: allow a new action after fresh readiness or quarantine after uncertain mutation. Chosen: terminal `OUTCOME_UNKNOWN` quarantines the operational entity until explicit authorized audited acknowledgement; reads remain allowed; later observations append and never rewrite the terminal result. Rejected: green readiness silently clearing action uncertainty because it can enable a double mutation. Status: **decided/FROZEN**. [REPO [project/roadmap.json — op_outcome_unknown_recovery](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

39. **Initial mutation vendor/mode — 2026-09-04–05.** Question: first controlled failover target. Chosen: classic Check Point ClusterXL only. Rejected for the first pilot: PAN and VSX/VSLS, because their identity and action semantics require separate adapters/evidence. Status: **decided initial scope**; see OP.2.0 P16 and current state. [REPO [docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md — P16](https://github.com/ozandurmus/neXus/blob/main/docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md)]

#### Product Control Plane and M-series decisions

40. **`pcp_console_registry_write_gate` — 2026-09-05.** Question: neither, candidate-only, or both manual/candidate enrollment in controlled loopback before DEPLOY.1A. Chosen: both may exist only under the frozen typed-intent, separate CLASS 0 first-contact, strict trust, positive identity, preview, explicit confirmation, audit-before-mutation, and single registry-path conditions. Rejected: unconfirmed writes and production inheritance. Status: **decided**; M9 implemented the available manual path. [REPO [project/roadmap.json — pcp_console_registry_write_gate](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

41. **`pcp_server_enrollment_exposure` — open.** Question: may enrollment leave loopback before OIDC/RBAC. Current recommendation: no. Rejected for now: compensating controls not defined by a frozen contract. Status: **open/blocked on DEPLOY.1A**. [REPO [project/roadmap.json — pcp_server_enrollment_exposure](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

42. **`pcp_auto_enrollment_policy` — 2026-09-05.** Question: automatic enrollment never versus future opt-in policy. Chosen for current horizon: no automatic persistent enrollment; every candidate still needs evidence, preview, and confirmation. Rejected: “everything discovered becomes inventory,” because discovery provenance is not enrollment authority. Status: **decided for current horizon, explicitly reopenable later**. [REPO [project/roadmap.json — pcp_auto_enrollment_policy](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

43. **`pcp_local_control_plane_storage` — 2026-09-05.** Question: keep registry filesystem + new SQLite metadata, migrate both, or postpone SQLite. Chosen: Option A—registry remains on its frozen filesystem backend; SQLite owns only new local control-plane metadata. Rejected: combined migration because it reopened PCP.1 unnecessarily; rejected no-SQLite because job/projection queries need a proper local metadata store. Status: **decided**. [REPO [project/roadmap.json — pcp_local_control_plane_storage](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

44. **`pcp_storage_engine` — open.** Question: production registry/job engine—existing PostgreSQL route, another engine, or filesystem until measured need. No engine chosen. Rejected: reading local SQLite as production selection. Status: **open; decide with production migrations/roles**. [REPO [project/roadmap.json — pcp_storage_engine](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

45. **`pcp_first_contact_trust_policy` — 2026-09-05.** Question: strict trust for all endpoints versus compatibility mode for some candidates. Chosen: strict SSH host-key/TLS trust before any credential submission for every endpoint. Rejected: TOFU, automatic trust, certificate bypass, candidate-based waiver, and credential-first probing. Status: **decided**. [REPO [project/roadmap.json — pcp_first_contact_trust_policy](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

46. **M8 implementation sequence — 2026-09-06.** Question: how to turn registry ids into identity-safe device targeting. Chosen sequence: M6 registry-keyed targets → M8.1 identity relationships → M8.2 endpoint trust lookup → M8.3 first-contact evidence → M8.4 resolver consumption → M7 real targeted collection. Rejected: direct endpoint substitution or operator assertion because neither establishes the required identity relationship. Status: **decided/FROZEN**, though M8.4 shipped before M8.3 real validation under a later narrow sequencing amendment. [REPO [docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md](https://github.com/ozandurmus/neXus/blob/main/docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md)]

47. **M8.3 real-environment deferral — 2026-09-07.** Question: perform the bounded identity-first-contact real-device gate immediately or defer it. Chosen: defer to backlog; do not promote M8.3 beyond `AUTOMATED_VALIDATED`; M7 remains blocked. Rejected: synthetic evidence satisfying the gate. Status: **decided sequencing amendment/deferred**. [REPO [project/backlog.json — m8_3_real_environment_validation](https://github.com/ozandurmus/neXus/blob/main/project/backlog.json)]

48. **M8 physical fingerprint persistence — 2026-09-07.** Question: persist the already-captured trust fingerprint in physical evidence only, add it to VSX context too, or leave the currency check permanently unable to pass. Chosen: physical artifact metadata only. Rejected: VSX-context copy because that artifact has no independent handshake; rejected no fix because M8.4 could never affirmatively establish currency. Status: **decided/implemented/merged**. [REPO [project/build_history.json — m8_evidence_host_key_fingerprint_not_persisted](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

49. **M9 candidate-id path — 2026-09-07.** Question: invent/borrow a candidate-id source inside M9 or defer until the M10 reconciliation projection. Chosen: honest stable refusal naming the M10/A6 dependency. Rejected: fabricated source or silent manual-only parity claim. Status: **decided/deferred**. [REPO [project/build_history.json — m9_enrollment_preview_confirmation_ui](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

50. **M9 pre-enrollment probe path — 2026-09-07.** Question: narrow direct admission-coordinator exception versus a throwaway pending registry stub so the existing registered-device path could run. Chosen: one M9-only documented/test-pinned exception; no registry mutation before confirmation. Rejected: pending stub because it creates provisional authority/state before enrollment confirmation. Status: **decided/implemented/merged**. [REPO [AI_HANDOVER.md — M9 round 2](https://github.com/ozandurmus/neXus/blob/main/AI_HANDOVER.md)]

51. **M9 credential-profile semantics — 2026-09-07.** Question: accept arbitrary well-formed references or only a reference that selects the real credential source. Chosen: one closed sentinel matching the executed source. Rejected: syntactically valid but semantically inert references. Status: **corrective decision/implemented**. [REPO [project/build_history.json — M9 risks_forward](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

52. **M9 confirmation consumption — 2026-09-07.** Question: list-scan-then-create versus atomic consume/audit creation. Chosen: deterministic audit id and one locked check-and-create. Rejected: process-local sequential reasoning because concurrent confirmations could both mutate. Status: **corrective decision/implemented**. [REPO [project/build_history.json — M9 risks_forward](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

53. **Path authority — 2026-08–09.** Question: build topology/path analysis internally or consume an external path result. Chosen: consume Tufin API results and apply rules to the returned devices. Rejected: internal topology engine because it duplicates an established authority and expands scope dramatically. Status: **human direction; repository durability UNKNOWN**. [PO-DIRECTION 2026-08–09, ChatGPT PO chat] → ratified DR-1

54. **Production timing — 2026-08–09.** Question: move/refactor for production now or continue local/corporate development. Chosen: do not move to production yet; production/container/pod hardening is a later explicit track and must not casually block the local product loop. Rejected: premature deployment refactor because it diverts from usable product capability. Status: **human direction; see also the repository's staged platform direction**. [PO-DIRECTION 2026-08–09, ChatGPT PO chat] → ratified DR-6

### §3 Rejected directions

- **Internal topology/path engine.** Rejected by the human PO; Tufin remains the path-discovery authority and neXus consumes its returned path. The rejection remains unless the human explicitly changes external-system strategy. [PO-DIRECTION 2026-08–09, ChatGPT PO chat] → ratified DR-1

- **Automatic rollback after uncertain failover.** Rejected because it is an unconfirmed second CLASS 2 mutation against unknown state; failback is a new typed action. [REPO [docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md — P12](https://github.com/ozandurmus/neXus/blob/main/docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md)]

- **Blind retry after the mutation boundary.** Rejected because a timeout/lost response cannot prove the first mutation did not execute. Use `OUTCOME_UNKNOWN` and quarantine. [REPO [docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md — P7/P10](https://github.com/ozandurmus/neXus/blob/main/docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md)]

- **Invented numeric flap or member-skew thresholds.** Rejected because cumulative/no-window evidence cannot support such numbers. Keep uncertainty visible; do not manufacture a PASS/FAIL boundary. [REPO [project/roadmap.json — D-F3](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- **Hostname, UI label, inferred ordinal, or client heuristic as identity authority.** Rejected because presentation identity is not security identity and browser code must not perform joins. [REPO [AGENTS.md — Evidence laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#evidence-laws)]

- **Automatic enrollment from discovery.** Rejected for the current horizon because discovery/candidate provenance is not authority to create persistent devices. [REPO [project/roadmap.json — pcp_auto_enrollment_policy](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- **Pending/throwaway Device Registry stub before M9 confirmation.** Rejected in favor of one narrow probe exception because provisional persistence would violate the enrollment-before-authority boundary. [REPO [AI_HANDOVER.md — M9 round 2](https://github.com/ozandurmus/neXus/blob/main/AI_HANDOVER.md)]

- **Trust-on-first-use, automatic host-key acceptance, TLS verification bypass, or credential-first probing.** Rejected because a mistyped/hostile endpoint must not receive credentials. [REPO [project/roadmap.json — pcp_first_contact_trust_policy](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- **A parallel diagnostic credential/network path.** Rejected unless separately reviewed; prefer the existing authenticated transport and bounded projection. [REPO [AGENTS.md — Diagnostic-path law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#diagnostic-path-law)]

- **Persisting raw vendor output for convenience.** Rejected absent an explicit evidence/forensics contract and privacy lifecycle. [REPO [AGENTS.md — Raw-evidence law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#raw-evidence-law)]

- **Server/production inheritance of local-loopback enrollment permission.** Rejected; server exposure reopens authorization and is blocked on DEPLOY.1A. [REPO [project/roadmap.json — pcp_server_enrollment_exposure](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- **Production hardening as a universal blocker for local product work.** Rejected as an operating assumption; only a technically relevant gate blocks the bounded local movement. Production readiness remains mandatory before production claims. [REPO [PROJECT_VISION.md — Platform direction](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#platform-direction)]

- **Mega-prompts and architecture re-litigation for routine fixes.** Rejected by the human PO because they slowed delivery, consumed limits, and reduced implementer confidence. [PO-DIRECTION 2026-09-05–07, ChatGPT PO chat] → ratified DR-3

- **Using council language to inflate confidence.** Rejected: no independent council execution may be claimed where only one author performed structured self-critique. [REPO [project/build_history.json — M3 revision history](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

### §4 Review heuristics

1. Look for the highest applicable authority and its exact status; if a draft, handover, chat statement, or lower authority is being used to authorize implementation over a frozen contract, it is a finding. [REPO [AGENTS.md — Authority hierarchy](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#authority-hierarchy)]

2. Look for a complete, validated `SESSION_START`/`SESSION_CLOSE` and the correct relay role; if a locator, narrative, unknown field, invented marker, or malformed packet is treated as authority, it is a finding. [REPO [docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md](https://github.com/ozandurmus/neXus/blob/main/docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md)]

3. Look for one coherent objective and explicit in/out scope; if a correction silently adds architecture, storage, credential paths, vendor commands, unrelated UI, or deployment work, it is a finding. [REPO [AGENTS.md — Mandatory build lifecycle](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#mandatory-build-lifecycle)]

4. Look for the real source seam and its tests before accepting a proposed change; if the design relies on an imagined producer, field, route, parser behavior, or call order, it is a finding. [REPO [AGENTS.md — Engineering laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#engineering-laws)]

5. Look for each identity/provenance join and ask what independently proves both sides; if equality is based on label, endpoint formatting, one-sided peer claims, or identifier coercion, it is a finding. [REPO [AGENTS.md — Evidence laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#evidence-laws)]

6. Look for trust-before-credential and reuse of the controlled network path; if credentials can be resolved/submitted before target-specific trust or a second transport path appears, it is a finding. [REPO [AGENTS.md — Diagnostic-path law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#diagnostic-path-law)]

7. Look for the mutation boundary, idempotency key, lock grain, ownership, and atomic consume semantics; if correctness depends on list-then-create, sequential execution, or a process-local check, it is a finding. [ASSISTANT confidence: high]

8. Look for reference fields that actually select behavior; if a `*_ref` is accepted but ignored or maps to no closed provider, it is a finding. This found M9's arbitrary `credential_profile_ref`. [ASSISTANT confidence: high]

9. Look for contract parity across every advertised input mode; if manual and candidate enrollment are both promised but no candidate producer exists, it is a finding, not permission to invent a producer. This found M9's candidate-id gap. [ASSISTANT confidence: high]

10. Look for exceptions to the canonical orchestration path; if an exception is not minimal, documented, unique, tested, and PO-authorized when it crosses a durable boundary, it is a finding. This found M9's pre-enrollment probe bypass. [ASSISTANT confidence: high]

11. Look for replay/confirmation races; if two concurrent requests can both pass the same precondition before either records consumption, it is a finding. This found M9's non-atomic confirmation path. [ASSISTANT confidence: high]

12. Look for the whole UI path: server payload/route, fixture, permanent click-through or DOM test, render harness, navigation entry point, accessibility, and report/console parity. If only the visible dialog exists, it is a finding. This found M9's missing fixture/harness coverage and missing Administration entry. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — HTML render harness](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#html-render-harness-mandatory-for-any-ui--payload-change)]

13. Look for validation proportional to blast radius: targeted first, affected subsystem next, full regression for shared core/schema/concurrency/security/UI milestone triggers, plus privacy and diff checks. If a broad change claims closure on compile-only or skipped harness evidence, it is a finding. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — Testing tiers](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#testing-tiers)]

14. Look for evidence classification: `IMPLEMENTED`, `AUTOMATED_VALIDATED`, `REAL_ENV_VALIDATED`, and `DONE` must match what actually ran. If fixture evidence is used to claim device behavior, it is a finding. [REPO [AGENTS.md — Mandatory build lifecycle](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#mandatory-build-lifecycle)]

15. Look for state reconciliation across roadmap, feature registry, backlog, build history, current state, and handover; if newest facts coexist with stale blockers/entry points/outcomes, it is a finding. [REPO [AGENTS.md — Project-state update rule](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#project-state-update-rule)]

16. Look for Git ancestry, actual PR state, exact head, green required checks, and whether the approved bytes are what merged; if a narrative says merged/clean without verifiable integration evidence, it is a finding. [ASSISTANT confidence: high]

### §5 Recurring engineering failure patterns

- **Self-authorized exceptions.** Detection: a canonical “every job/path does X” invariant gains an undocumented `if` branch. Correction: stop only the dependent action, post a durable question, compare narrow exception versus redesign, obtain PO decision, then pin uniqueness with a test. [REPO [AI_HANDOVER.md — M9 round 2](https://github.com/ozandurmus/neXus/blob/main/AI_HANDOVER.md)]

- **Scope widening inside a fix.** Detection: privacy, DLP, CI, parser, or metadata correction begins changing architecture or unrelated modules. Correction: preserve the narrow defect objective and file a separate backlog/movement for incidental debt. [REPO [AGENTS.md — Engineering laws](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#engineering-laws)]

- **`DONE` from automated evidence.** Detection: network-facing behavior has only fixtures/unit tests or no approved real-device run. Correction: cap at `AUTOMATED_VALIDATED`, record the exact real-env debt, and keep consumers blocked when the frozen gate requires it. [REPO [AGENTS.md — Mandatory build lifecycle](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#mandatory-build-lifecycle)]

- **Stale handover/state projection.** Detection: handover or feature criterion says open/not-built while current state/build history says merged, or vice versa. Correction: authoritative JSON first, then rewrite projections; never make handover a competing authority. [REPO [AGENTS.md — Handover economy](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#handover-economy)]

- **Invented vendor semantics.** Detection: a field/command name is treated as meaning, or generic model knowledge fills an official-doc/real-env gap. Correction: mark `UNKNOWN`, identify the exact source/measurement needed, and freeze only the safe minimum. [REPO [AGENTS.md — Vendor semantics law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#vendor-semantics-law)]

- **Identity forced to match.** Detection: zero stripping, integer casting, hostname normalization, inferred member ordinal, management/control-link equivalence, or one-sided peer claims. Correction: opaque comparison, independent observations, relationship-only reporting, and `MISMATCH/AMBIGUOUS/NOT_EVALUABLE` when unproven. [REPO [AGENTS.md — Identity law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#identity-law--identifiers-are-opaque)]

- **Semantically inert reference fields.** Detection: API accepts a plausible reference but execution always uses the same implicit provider. Correction: either implement closed resolution or constrain to the actual sentinel; never imply selection that does not happen. [REPO [project/build_history.json — M9](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

- **Check-then-create concurrency.** Detection: uniqueness/confirmation is enforced by a list scan before a separate write. Correction: deterministic key plus atomic backend transaction/lock, proven with real concurrent threads/processes where relevant. [REPO [project/build_history.json — M9](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

- **UI exists visually but not operationally covered.** Detection: ad hoc click-through, no permanent fixture, skipped render harness, or one promised navigation entrance missing. Correction: fixture + permanent harness/click-through + both delivery modes + intended navigation route. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — HTML render harness](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#html-render-harness-mandatory-for-any-ui--payload-change)]

- **Plausible root cause accepted before parsing the primary artifact.** Detection: infrastructure/identity is blamed while the actual YAML, JSON, output file, or source was not parsed locally. Correction: validate the primary artifact first and record the corrected diagnosis rather than rewriting history. The CI zero-job incident was a YAML syntax defect, not automation identity. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — post-merge YAML incident](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#ci-validation-policy-canonical--devtest1-final-topology-2026-09-06)]

- **Predicted test evidence written before execution.** Detection: exact pass counts appear before a real run or later differ from the claimed baseline. Correction: only report command-backed evidence, preserve the correction, and do not round away failures/skips. [REPO [project/build_history.json — M3 correction history](https://github.com/ozandurmus/neXus/blob/main/project/build_history.json)]

- **Process ceremony replacing product progress.** Detection: routine work repeatedly asks for architecture/council/model decisions or full-suite reruns without a risk trigger. Correction: one bounded movement, normal reasoning, focused tests, one real stop condition. [PO-DIRECTION 2026-09-05–07, ChatGPT PO chat] → ratified DR-3

### §6 Sequencing rationale

- The top-level order is `SEE → VERIFY → TRACE → RECOVER → OPERATE`: observation and identity precede controlled change because later actions consume the earlier evidence grades. [REPO [PROJECT_VISION.md](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md)]

- The M-series exists to turn existing collectors/reports into a persistent, targetable, interactive control plane: local metadata store, collector target seam, registry-keyed jobs, identity/trust/evidence relationship, resolver consumption, real targeted collection, enrollment, then reconciliation/capability projection. The DB and interactive UI are enabling infrastructure for compliance, backup, and failover—not an alternative product track. [REPO [docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md — movement sequence](https://github.com/ozandurmus/neXus/blob/main/docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md#20-roadmap-reconciliation-and-movement-sequence)]

- M4 precedes durable job projections because query-shaped local control-plane metadata needs SQLite, while the Device Registry deliberately remains on its frozen filesystem backend. M5/M6 precede M7 because a browser/job must carry stable `device_id`, not raw endpoint/command data. [REPO [project/roadmap.json — pcp_local_control_plane_storage](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- M8.1–M8.4 precede real M7 collection because registry identity cannot be substituted directly for collector identity. Trust must precede credential submission, evidence must prove the mapping, and the resolver must check current provenance/currency. [REPO [docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md](https://github.com/ozandurmus/neXus/blob/main/docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md)]

- M9 could deliver manual local enrollment after M8 primitives existed, but candidate-id enrollment correctly remains dependent on M10's registry↔evidence reconciliation projection. M9 must not implement M10 by stealth. [REPO [AI_HANDOVER.md — M9](https://github.com/ozandurmus/neXus/blob/main/AI_HANDOVER.md)]

- Compliance is deliberately split: framework mappings and data-driven checks can consume existing evidence now; curated command primitives need their own command gate/real validation; user-authored/signed organization packs and UI editing wait for the production authorization boundary. [REPO [project/feature_registry.json — compliance_engine](https://github.com/ozandurmus/neXus/blob/main/project/feature_registry.json)]

- Backup/recovery is deliberately separate from redacted configuration evidence because redacted evidence is non-restorable. Store/encryption/manifest/retention precede collectors; artifact validation precedes any restore claim; `RESTORE_PROVEN` requires an actual lab restore. The CP backup criterion remains open pending its watched real run. [REPO [project/feature_registry.json — native_backup_foundation](https://github.com/ozandurmus/neXus/blob/main/project/feature_registry.json)]

- Failover order is readiness evidence → plan/dry-run → controlled execution. The plan must be explicit before mutation; execution then adds authorization, same-workflow preflight, confirmation, operational-entity lock, one submission, independent verification, and audit. [REPO [project/feature_registry.json — failover tracks](https://github.com/ozandurmus/neXus/blob/main/project/feature_registry.json)]

- The first real failover pilot remains classic ClusterXL. PAN and VSX/VSLS execution follow only after their own identity/action semantics and adapters are proven; read-only readiness support does not authorize mutation support. [REPO [docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md — P16](https://github.com/ozandurmus/neXus/blob/main/docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md)]

- Production platform work follows a usable local product loop unless a specific current movement genuinely depends on it. Local loopback permission never automatically becomes server permission. [REPO [PROJECT_VISION.md — Platform direction](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#platform-direction)]

- The current roadmap's `now_next.next` names a consciously **deferred** M8.3 real-environment validation. A deferred item is not an executable NEXT; leaving it there risks making the roadmap appear stalled while M10, compliance, backup, and failover preparation remain actionable candidates. I recommend the human PO choose one real actionable `NEXT` while retaining M8.3 as deferred debt, rather than letting a deferred row occupy the sole next slot. [ASSISTANT confidence: high]

- The human PO's expressed product priority is to finish the M-series control-plane loop so compliance, unfinished backup, and failover can be exercised through a real database-backed interactive UI. I believe the repository broadly reflects this direction, but the exact post-M9 priority among M10, compliance closure, backup real validation, and failover preparation still requires ratification. [PO-DIRECTION 2026-09-07, ChatGPT PO chat] → ratified DR-2

### §7 Council

- The repository's “council” is a set of decision lenses, not evidence of an installed or independently executed council. Past M3 council material is explicitly recorded as one author's structured self-critique, not independent validation. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Senior Python Architect** protects module boundaries, maintainability, typing, failure behavior, and reuse of mature seams. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Network Security Engineer** protects least privilege, operational safety, blast radius, network-command classification, and change control. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Check Point / VSX Engineer** protects Gaia/Expert/Clish context, ClusterXL/VSX/VSLS distinctions, and evidence/action semantics specific to that platform. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Palo Alto Engineer** protects Panorama versus device truth, HA mode/peer evidence, TLS/API semantics, and platform-specific action boundaries. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Multi-vendor Automation Engineer** protects a normalized product model without erasing vendor-native meaning or forcing generic mutation primitives. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Configuration Management Specialist** protects intent/current/effective distinctions, revision/history semantics, and non-restorable versus restorable artifacts. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Data / Inventory Architect** protects identity keys, cardinality, provenance, schema ownership, migrations, retention, and reconciliation. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **DevSecOps / Platform Engineer** protects deployment boundaries, CI economy, secrets, roles, runtime paths, containers, and operational observability. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Security Reviewer** protects trust-before-credential, fail-closed behavior, authorization separation, privacy/DLP, and abuse cases. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Test Automation Engineer** protects executable acceptance criteria, concurrency tests, fixture coverage, render harnesses, and risk-based regression. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **UI/UX Product Designer** protects operator comprehension, contextual actions, navigation integrity, accessibility, and honest empty/unknown states. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Technical Product Owner** protects one objective, value, sequencing, acceptance evidence, explicit tradeoffs, and a real decision rather than endless option expansion. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Network/Security Manager** protects maintainability in the operating organization, separation of duties, emergency practicality, and change-management fit. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- **Business/Executive stakeholder** protects measurable risk reduction, auditability, roadmap clarity, and time-to-usable-product rather than engineering ceremony. [REPO [PROJECT_VISION.md — Product decision lenses](https://github.com/ozandurmus/neXus/blob/main/PROJECT_VISION.md#product-decision-lenses)]

- Invoke these lenses only for a durable high-impact decision: new identity/security authority, storage/concurrency model, vendor mutation semantics, CLASS 2+, major product UX contract, production exposure, or frozen-contract review. Do not invoke them for routine implementation, narrow defects, mechanical state reconciliation, or work already determined by a frozen contract. [ASSISTANT confidence: high]

- A proper council output should separate evidence, assumptions, options, per-seat risks, dissent, and the exact questions requiring human PO ratification. It should never self-ratify or masquerade as multiple independent reviews when one model authored all seats. [ASSISTANT confidence: high]

- The human PO asked not to run council-style work in the ChatGPT PO chat because of token/credit cost; if a council is genuinely necessary during the current Claude-led period, route the bounded review to the engineering environment and return only the decision packet. This is an operating preference, not product law. [PO-DIRECTION 2026-09-05–07, ChatGPT PO chat] → ratified DR-7 (SUPERSEDED by GOV.PO.1)

- I remember no council verdict or dissent that I can confidently classify as both material and absent from repository documentation. Any contrary claim should be `UNKNOWN` until the named historical contract is read. [ASSISTANT confidence: medium]

### §8 Real-environment constraints (sanitized)

- Development environments include a local workstation profile and a corporate workstation profile; shell/tool availability differs. A command such as `py` may exist in one shell and not another, and the project interpreter/venv must be discovered from repository/environment evidence rather than assumed. [PO-DIRECTION 2026-09-07, VS Code/Claude relay discussion] → ratified DR-8

- The corporate workstation can support direct interactive UI validation through VS Code/browser tooling. The human may enter secrets locally when prompted; agents must never ask for those values in chat, relay, GitHub, screenshots, or logs. [PO-DIRECTION 2026-09-07, ChatGPT PO chat] → ratified DR-8

- Real device contact is separately authorized from code execution. Physical capability, corporate-network presence, or a green test does not grant permission. The action must be bounded by platform class, target class, command/purpose, and human approval. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — Human/agent responsibility split](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#human--agent-responsibility-split)]

- For Check Point, the validated interaction pattern uses a persistent Expert-shell session, explicit `clish -c` for Gaia commands when required, and explicit VS context switching for VSX reads. Do not reconnect per command, nest SSH clients, or blind-retry. [REPO [AGENTS.md — Check Point](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#check-point)]

- PAN management addressing and HA/control-link addressing are distinct identity planes. One must not substitute for the other. Raw values are intentionally omitted here. [REPO [AGENTS.md — Palo Alto](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#palo-alto)]

- Endpoint trust is target-specific and precedes credential submission: approved known-host/host-key handling for SSH and CA/certificate validation for TLS; no TOFU or verification bypass. [REPO [project/roadmap.json — pcp_first_contact_trust_policy](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- Per-vendor contact concurrency remains one until vendor-specific real-environment evidence supports increasing it. Stability outranks collection speed. [REPO [CURRENT_STATE.md — Open blockers](https://github.com/ozandurmus/neXus/blob/main/CURRENT_STATE.md)]

- Corporate DLP/privacy gates prohibit secrets, raw identities, sensitive local paths, runtime artifacts, and credential material from tracked/shareable surfaces. Report file and location/classification for a finding, never the matched value. [REPO [AGENTS.md — Privacy and DLP](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#privacy-and-dlp)]

- Corporate Git actions are PO-authorization-controlled. An explicitly authorized agent may execute; a valid packet or green CI alone does not authorize push/PR/merge. [REPO [AGENTS.md — Git authority and execution law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#git-authority-and-execution-law)]

- UI/payload changes require permanent fixtures where applicable, the HTML render harness, full regression when the UI milestone/blast radius requires it, and the privacy gate. Node/happy-dom is primary when available; Playwright/Chromium is the real-browser fallback, not authority to skip permanent coverage. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — HTML render harness](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#html-render-harness-mandatory-for-any-ui--payload-change)]

- Real-environment reports must be sanitized to relationship/status vocabulary. No raw device identity, secrets, endpoints, or key material may be copied into the result. [REPO [AGENTS.md — Sensitive identity reporting law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#sensitive-identity-reporting-law)]

### §9 Stakeholders and external constraints

- The human Product Owner ratifies product direction, frozen contracts, scope changes, real-environment/device contact, sensitive actions, deployment acceptance, and Git integration decisions. An assistant recommends and records evidence; it does not become the ultimate authority. [REPO [AGENTS.md — Git authority and execution law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#git-authority-and-execution-law)]

- Network-security/change-management stakeholders must sign the controlled-failover review before a real CLASS 2 pilot. The current review package is drafted but unsigned; architecture and unit-tested unwired components are not operational approval. [REPO [CURRENT_STATE.md — Open blockers](https://github.com/ozandurmus/neXus/blob/main/CURRENT_STATE.md)]

- The human operator supplies/enters credentials only in the approved local environment and observes bounded real-device tests. Agents report relationships and outcomes without retrieving or reproducing the values. [REPO [docs/AI_DEVELOPMENT_PROTOCOL.md — Human/agent responsibility split](https://github.com/ozandurmus/neXus/blob/main/docs/AI_DEVELOPMENT_PROTOCOL.md#human--agent-responsibility-split)]

- Corporate policy binds DLP/privacy, network access, credential handling, Git authorization, and use of approved environments. Exact corporate policy names and signatory identities are `UNKNOWN`; do not invent them. [ASSISTANT confidence: high]

- “Production” means more than running on a corporate workstation: managed server/runtime, OIDC/RBAC, trusted production TLS/SSH, database role separation, secret management, report-only publication boundaries, audit retention/observability, off-host recovery custody, and restore-drill evidence. See `CURRENT_STATE.md`; exact deployment topology remains unsettled. [REPO [CURRENT_STATE.md — Production posture](https://github.com/ozandurmus/neXus/blob/main/CURRENT_STATE.md)]

- Tufin is the external path-discovery authority for the future path-based rule-placement capability. Its API availability, schema, authentication, and organizational ownership are external dependencies and were not verified in this extraction. [PO-DIRECTION 2026-08–09, ChatGPT PO chat] → ratified DR-1

- Check Point and Palo Alto official documentation plus bounded approved real-environment evidence constrain vendor-semantic decisions; general model knowledge is not a sign-off source. [REPO [AGENTS.md — Vendor semantics law](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#vendor-semantics-law)]

- Claude, Codex, Copilot, or any repository-defined subagent are implementation/review instruments, not stakeholders with independent ratification power. The active movement must name ownership; the PO remains the decision authority. [REPO [AGENTS.md — Authority hierarchy](https://github.com/ozandurmus/neXus/blob/main/AGENTS.md#authority-hierarchy)]

### §10 Things you believe are in the repository but may not be

- I relied on “Tufin supplies path discovery; neXus does not build topology/path analysis” as settled product direction, but I did not find/verify a current canonical file stating it. [ASSISTANT confidence: high]

- I relied on the human's formulation that the M-series ends by establishing the DB-backed interactive control plane needed to finish compliance, backup, and failover, but the exact “M-series completion” definition may be distributed rather than stated in one file. [ASSISTANT confidence: medium]

- I relied on “testing requests that require the human should be put in backlog while unrelated work continues,” but I am not sure the repository states that operating rule beyond specific deferred validations. [ASSISTANT confidence: medium]

- I relied on one implementation owner per coupled movement and one integration owner for parallel work; the repository strongly supports bounded movements but may not explicitly prohibit multiple independent worktrees. [ASSISTANT confidence: medium]

- I relied on “no production migration/container refactor now” as a human sequencing direction; the repository states staged platform direction and current non-production posture, but may not preserve the exact temporary prohibition. [ASSISTANT confidence: high]

- I relied on the human being able to run interactive UI tests and enter credentials locally on a corporate workstation; this operational capability is likely intentionally absent from repository authority. [ASSISTANT confidence: high]

- I relied on the PO preference that new movement prompts include a short Turkish explanation of feature, UI location, blocker removed, security relevance, and necessity before the English engineering packet. I am not sure the repository records this human-facing communication format. [ASSISTANT confidence: high]

- I relied on a current tool allocation in which Claude owned M9 implementation and Codex/ChatGPT performed governance/PO review. M9's historical record states that ownership, but no durable rule should assume the same model/vendor for later movements. [ASSISTANT confidence: high]

### §11 Open doubts

- `project/roadmap.json` names deferred M8.3 real-environment validation as the sole `next`. The repository contract says NEXT is exactly one, but a deferred row is not executable. Human PO sequencing is needed to decide whether the actionable next movement is M10/control-plane reconciliation, a compliance closure, backup real validation, failover preparation, or deliberate M8.3 execution. [ASSISTANT confidence: high]

- Candidate-based enrollment is advertised in the local enrollment decision but still lacks its M10 candidate/reconciliation producer. The exact M10 contract, candidate identity basis, lifecycle, and stale/contradictory reconciliation behavior remain to be verified before implementation. [REPO [AI_HANDOVER.md — M9 follow-up](https://github.com/ozandurmus/neXus/blob/main/AI_HANDOVER.md)]

- M7 remains blocked on one genuine M8.3 real-environment-validated relationship. The human intentionally deferred that test; it is unclear when the product wants to pay this debt relative to M10/UI work. [REPO [CURRENT_STATE.md — Exact next build](https://github.com/ozandurmus/neXus/blob/main/CURRENT_STATE.md)]

- PAN B2 pair identity remains unestablished after conflicting observations. It blocks PAN CLASS 2 and must not be closed through normalization or an unrelated movement. [REPO [project/roadmap.json — D-V3b](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- CP configured-recovery vendor semantics remain unknown even though their readiness role is advisory-exempt. CLASS 2 still needs an approved semantic or an explicit safe product decision. [REPO [project/roadmap.json — D-V7b](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- OP.2 has frozen architecture and several implemented unwired foundations, but authorization, protected entry point, production trust hardening, signed change/network review, and real pilot evidence remain. The feature registry's “implementation not started” wording may now understate actual foundation progress. [ASSISTANT confidence: high]

- CP native backup implementation remains `in_progress` until its watched single-target real-environment run; restore proof is distinct and requires a real lab restore. The timing and environment for those tests remain external. [REPO [project/feature_registry.json — native_backup_foundation](https://github.com/ozandurmus/neXus/blob/main/project/feature_registry.json)]

- Compliance's rule engine is partly delivered, but curated read-only command primitives, signed organization packs/UI editor, and the final evidence-report model remain incomplete and gated differently. The next bounded compliance slice is not selected. [REPO [project/feature_registry.json — compliance_engine](https://github.com/ozandurmus/neXus/blob/main/project/feature_registry.json)]

- Production storage engine, server enrollment exposure, four-eyes policy, operational-write target count/reason retention, scheduler editing, degraded verdict, emergency path, and continuity thresholds remain open roadmap decisions. [REPO [project/roadmap.json — open_decisions](https://github.com/ozandurmus/neXus/blob/main/project/roadmap.json)]

- I observed a likely state drift: the current M9 record says Administration → Device Management was built, while the `left_vertical_product_navigation.no_enrollment_affordance` criterion text still says that second entry point is not built. Verify and reconcile rather than choosing one silently. [ASSISTANT confidence: high]

- I observed a second likely state drift: `failover_controlled_execution` is `planned` and says implementation is not started, while `CURRENT_STATE.md` says the adapter, member session, and preflight/eligibility components are implemented and unit-tested but unwired. The status may intentionally mean “product execution not started,” but the wording should be made unambiguous. [ASSISTANT confidence: high]

- The exact corporate sign-off chain and whether a bounded local failover pilot requires full production OIDC/RBAC or a separately approved pilot boundary remain insufficiently explicit to me. Do not infer the answer from workstation location. [ASSISTANT confidence: medium]

### §12 Advice to your successor

#### The first three mistakes a fresh assistant will make

1. It will read `AI_HANDOVER.md`, a relay locator, or the newest chat message as authority and skip the repository hierarchy. Correction: cold-start in the mandated order, locate the active frozen contract, validate the packet, and report contradictions rather than reconciling them silently. [ASSISTANT confidence: high]

2. It will turn a plausible vendor/identity relationship into certainty—especially management versus direct truth, display labels versus security ids, or one-sided peer evidence versus reciprocal proof. Correction: preserve the separate evidence grades and emit `UNKNOWN/MISMATCH/AMBIGUOUS` when the join is not proven. [ASSISTANT confidence: high]

3. It will try to “finish faster” by combining a deferred producer, a UI affordance, a storage change, and a real-device test into one movement, then call it done from automated evidence. Correction: one objective, one owner, explicit gate, proportional tests, and the honest lifecycle state. [ASSISTANT confidence: high]

#### The first three questions to ask the human Product Owner

1. “`now_next.next` is deferred M8.3. Which single **actionable** movement do you ratify now: run that bounded real-environment gate, start M10 reconciliation/candidate production, close a compliance slice, close backup validation, or advance the next failover prerequisite?” [ASSISTANT confidence: high]

2. “For this episode, what exact real-environment authority is granted—none, read-only against one approved target class, or a separately reviewed operational action—and which evidence must remain local?” [ASSISTANT confidence: high]

3. “Which decisions may the repository-defined PO assistant make routinely, and which must always return to you: frozen-contract changes, new commands/credentials/identity authority, production exposure, device contact, waivers, and merge?” [ASSISTANT confidence: high]

### §13 Second pass — items omitted or only indirectly captured

Supplied by the previous assistant after re-reading its own output; same
provenance rules.

- The original feature brief also included rule consolidation, shadow-rule cleanup, schedule-object detection, bulk disable/delete based on last-hit age, and placing schedule-bearing rules on the Tufin-provided path. These may not yet be durably represented as one coherent product record. [PO-DIRECTION 2026-08–09, ChatGPT PO chat] → ratified DR-9a
- Before each engineering prompt, the PO wanted a short Turkish explanation covering the feature, UI location, benefit, blocker removed, security relevance, and necessity. This is a communication preference, not product architecture. [PO-DIRECTION 2026-09 period, ChatGPT PO chat] → ratified DR-9b
- When human testing is required but deliberately deferred, the preference was to record it in backlog and continue with unrelated authorized work rather than repeatedly stopping the project. [PO-DIRECTION 2026-09 period, ChatGPT PO chat] → ratified DR-9c
- Once a movement is accepted, the previous PO assistant was expected to supply the exact next prompt without repeated routine confirmation or "if you want" questions. It should not invent work merely to keep an agent occupied. [PO-DIRECTION 2026-09 period, ChatGPT PO chat] → ratified DR-9d
- The temporary subscription/tooling plan (Claude through the current billing period, ChatGPT Plus for PO review, and a possible later switch to Codex) is volatile and should not become repository product authority. [PO-DIRECTION 2026-09-07, ChatGPT PO chat] → ratified DR-9e
- Exact model names and subscription prices were omitted. Repository routing should remain task-driven and vendor-neutral; current commercial plans can change independently. [ASSISTANT confidence: high]
- Parallel development policy is not fully settled. "One writer for a coupled movement" is established operational practice, but independent worktrees/lanes may later be useful for compliance, backup, and failover. This needs an explicit integration-owner contract before adoption. [ASSISTANT confidence: medium]
- Earlier "device access is deferred" statements were omitted because later M8/OP/RB work introduced bounded real-environment gates. Treat the old blanket statement as superseded, not current authority. [ASSISTANT confidence: high]
- Exact PR numbers, commit hashes, test totals, workstation paths, and environment-install commands were mostly omitted because `project/build_history.json` already owns that evidence and such details are not durable product direction. [ASSISTANT confidence: high]
- All raw device identities, endpoints, usernames, credentials, host-key material, and locally sensitive values were deliberately excluded. [REPO AGENTS.md — Sensitive identity reporting law]

### §14 PO orchestration operating efficiency (2026-09-08)

Decided directly by the Product Owner in the live PO/orchestrator session on
2026-09-08, after monitoring an eight-movement batch
(`relay/NXS-LOCAL-0008`–`0018`) surfaced several avoidable cost/context
drivers. Effective immediately as PO operating defaults; none of these
require a repository code change — process discipline, not new authority,
and none relax `AGENTS.md`/`CLAUDE.md`'s existing reasoning-routing law.

55. **Reasoning-tier scaling by scope, not one fixed default — 2026-09-08.**
    Question: keep every movement at "Sonnet 5, normal" regardless of how
    narrow its scope is, or scale with scope. Chosen: for a narrow,
    mechanical, already-fully-specified movement (a single bookkeeping
    merge-conflict fix, a one-file test-isolation fix), `Sonnet 5, low` or
    `Sonnet 5, medium` is acceptable when the PO's own `SESSION_START`
    packet already resolves the ambiguity end to end; `Sonnet 5, normal`
    remains the default for ordinary deterministic implementation;
    `Opus, medium-high` (or "Fast") is reserved for freezing a
    contract/design document itself (new architecture, a cross-subsystem
    decision, a security/privacy boundary) — never for routine
    implementation against an already-frozen contract. Rejected: a single
    fixed tier for every movement, which over-spends on trivial mechanical
    fixes. Status: **decided operating direction**, applied via each
    movement's own `recommended_reasoning` field going forward.
    [PO-DIRECTION 2026-09-08, this session]
56. **Wave-based dispatch over all-at-once — 2026-09-08.** Question:
    dispatch every queued/approved movement in one batch (as this session
    did with eight concurrent movements) or stagger them. Chosen: dispatch
    in bounded waves (a lower `max-workers`, e.g. 3-4 concurrent), opening
    the next wave once the current one clears, rather than opening the full
    queue at once. Rejected: opening everything immediately, which forces
    continuous, expensive full-batch status monitoring. Status: **decided
    operating direction**. [PO-DIRECTION 2026-09-08, this session]
57. **Event-driven monitoring as the default over fixed-interval polling —
    2026-09-08.** Question: default to a fixed short polling interval
    (e.g. `/loop 1m`) for movement monitoring, or watch for state changes
    and poll only as a fallback. Chosen: default to event-driven monitoring
    (e.g. a `Monitor` against a relay file/process) with a longer fallback
    loop (5-10+ minutes); a short interval is used only when a movement is
    already known to be near completion. Rejected: a 1-minute fixed loop as
    the default — it spends a full turn every minute regardless of whether
    anything changed. Status: **decided operating direction**.
    [PO-DIRECTION 2026-09-08, this session]
58. **Batched governance commits over one commit per edit — 2026-09-08.**
    Question: commit each individual backlog/governance-file edit
    separately (as this session did, four separate commits to
    `project/backlog.json` in one sitting) or batch related edits into one
    commit. Chosen: batch multiple related governance-file edits (backlog
    additions, roadmap notes, direction-record entries) planned within the
    same PO turn into a single commit, unless the user asks to see them
    land separately. Rejected: one commit per edit, which multiplies
    `git fetch`/merge round-trips against a fast-moving `origin/main`.
    Status: **decided operating direction**. [PO-DIRECTION 2026-09-08, this
    session]
59. **PO reference notes over re-reading large source files in full —
    2026-09-08.** Question: re-read a large tool source file (e.g.
    `scripts/orchestrator.py`, `scripts/local_relay.py`) in full whenever
    its exact behavior (a required CLI flag, a resume/dispatch decision
    rule) is needed again, or keep a durable compact reference. Chosen:
    capture the operational facts the PO session repeatedly needs (required
    flags, the `decide_start` resume/dispatch/refuse rule, the
    CLOSED-relay append rule, the `git.base`/`git.lane` reuse pattern for
    completing a PR whose original relay is closed) as a small persistent
    PO-session reference instead of re-reading the full source file each
    time; the real source is still read before acting on anything the
    reference does not cover or that looks stale. Rejected: full re-reads
    as the default habit. Status: **decided operating direction**,
    reference captured 2026-09-08. [PO-DIRECTION 2026-09-08, this session]

## Decisions migrated from roadmap.json (2026-09-11)

GOV.ORCH.8 2.1: these decisions were already closed in `project/roadmap.json`'s `open_decisions`; moved here verbatim (post-redaction) as the file's size-budget narrative move.

### tufin_path_authority — Does neXus build its own topology/path engine for path-based policy placement, or consume an external path authority?

- **area**: Product scope / external systems
- **options**: ['Consume Tufin-provided device paths and apply rules to the returned devices', 'Build an internal topology/path engine']
- **recommendation**: Consume Tufin; an internal engine duplicates an established authority and expands scope dramatically.
- **decide_by**: DECIDED 2026-09-08 (direction-record ratification)
- **decided_on**: 2026-09-08
- **decision**: DECIDED -- neXus consumes Tufin-provided paths and does not build an independent topology/path engine. Missing or insufficient Tufin evidence stays explicit (UNKNOWN / INSUFFICIENT_EVIDENCE); no fallback path authority is invented. Tufin API availability, schema, authentication and organizational ownership are external dependencies, not yet verified in the repository. Ratified with docs/design/PRODUCT_DIRECTION_RECORD.md DR-1.

### gov_relay_1_protocol — What is the canonical GitHub-issue relay convention for Codex/Claude movement handoffs?

- **area**: Agent session governance
- **options**: ['A locator plus canonical issue-body/final-comment packets and a closed intermediate-comment vocabulary', 'Ad hoc chat phrases and unconstrained issue comments']
- **recommendation**: Use the canonical governed relay contract so a locator cannot be mistaken for authority and malformed handoffs fail before implementation.
- **decide_by**: DECIDED 2026-09-07 at GOV.RELAY.1 freeze; question-routing amendment approved 2026-09-07
- **decided_on**: 2026-09-07
- **decision**: DECIDED -- RELAY_READY owner/repository#issue is locator-only; the issue body is exactly one validated SESSION_START packet; the final engineering comment is exactly one validated SESSION_CLOSE packet; intermediate comments use only RELAY_ACK, RELAY_NOTE, RELAY_QUESTION, RELAY_DECISION or RELAY_CORRECTION; RELAY_START and RELAY_END are invalid; only the Product Owner may authoritatively issue RELAY_DECISION. Every material question requiring Product Owner resolution MUST be posted as RELAY_QUESTION on the active relay before its dependent action and remains open until a matching Product Owner RELAY_DECISION or higher repository authority resolves it; direct chat may explain but does not replace the durable question. The NEXUS_SESSION_PACKET v2 schema and parser are unchanged.

### op_degraded_verdict — Support DEGRADED_PROCEED_WITH_RISK (per-risk operator acceptance) in v1, or restrict to SAFE_TO_FAILOVER / UNSAFE_DO_NOT_FAILOVER / INSUFFICIENT_EVIDENCE until there is field experience?

- **area**: OP.0 Readiness Assessment
- **options**: ['include DEGRADED in v1', 'SAFE / UNSAFE / INSUFFICIENT only in v1']
- **recommendation**: SAFE / UNSAFE / INSUFFICIENT only in v1; add DEGRADED once the battery is validated against real clusters.
- **decide_by**: DECIDED 2026-09-09 (OP.1 contract freeze)
- **decided_on**: 2026-09-09
- **decision**: DECIDED -- Option A: SAFE / UNSAFE / INSUFFICIENT (+ NOT_A_FAILOVER_UNIT) only. DEGRADED_PROCEED_WITH_RISK stays structurally unreachable until real-field calibration. Ratified at the OP.1 contract freeze, docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md section 8.

### C-D1 — Approve fastapi + uvicorn as an optional dependency set (requirements-console.txt), keeping the core CLI/report path at its current four dependencies?

- **area**: CON.x Operator Console
- **options**: ['Approve as optional extra', 'Stdlib http.server only', 'Decline the console transport']
- **recommendation**: Approve. Boundary-level request validation is a security control and it is the same stack the later server control plane needs; the optional-extra pattern already exists for requirements-postgres.txt.
- **decide_by**: CON.1 contract review
- **decided_on**: 2026-09-01
- **decision**: Approve as optional extra

### C-D2 — Local authentication model for the console listener.

- **area**: CON.x Operator Console
- **options**: ['Cookieless per-launch bearer token in the URL fragment', 'Cookie session', 'No auth, loopback bind only']
- **recommendation**: Cookieless per-launch bearer token. No cookie means no ambient credential, which makes the local-CSRF class structurally impossible rather than mitigated. 'It is only localhost' is the failure mode, not the control.
- **decide_by**: CON.1 contract review
- **decided_on**: 2026-09-01
- **decision**: Cookieless per-launch bearer token in the URL fragment

### C-D3 — Provenance vocabulary: add Provenance.CONSOLE = 'console', or reuse 'manual' for UI-triggered runs?

- **area**: CON.x Operator Console
- **options**: ["Add 'console'", "Reuse 'manual'"]
- **recommendation**: Add 'console'. A UI-triggered device action must stay distinguishable from a CLI one in every manifest and audit record; conflating them destroys the audit trail on the first day it matters.
- **decide_by**: CON.2 contract review
- **decided_on**: 2026-09-01
- **decision**: Add 'console'

### D-V4 — PAN running-sync location (state XML sibling vs all).

- **area**: OP.0b.0 Vendor Failover Preflight Evidence
- **options**: ['Confirm from official vendor documentation']
- **recommendation**: Closed; no further action needed for freeze. Parser implementation (S2) remains owed.
- **decide_by**: resolved -- official PaloAltoNetworks GitHub source
- **decided_on**: 2026-09-03
- **decision**: CLOSED_BY_DOCS -- running-sync/running-sync-enabled confirmed at group scope, sourced from show high-availability state (already-REQUIRED P2), via a verbatim read of the official PaloAltoNetworks pan-os-upgrade-assurance GitHub repository.

### D-V5b — CP VSX failover-statistics applicability (VS0-only / per-VS / unsupported).

- **area**: OP.0b.0 Vendor Failover Preflight Evidence
- **options**: ['N/A -- not required by the frozen battery']
- **recommendation**: Dropped from the active blocking list.
- **decide_by**: resolved -- not load-bearing
- **decided_on**: 2026-09-03
- **decision**: Not load-bearing -- the frozen minimum battery runs failover-statistics only at the physical/VS0 level (same as the rest of the ClusterXL battery), never per-VS. The source pack's VSX-applicability question has no frozen check that depends on its answer.

### D-V7a — CP recovery/preemption behavior semantics.

- **area**: OP.0b.0 Vendor Failover Preflight Evidence
- **options**: ['Confirm from official vendor documentation']
- **recommendation**: Closed; no further action needed for freeze.
- **decide_by**: resolved -- official Check Point ClusterXL Admin Guide
- **decided_on**: 2026-09-03
- **decision**: CLOSED_BY_DOCS -- "Maintain current active" vs "Switch to higher priority Cluster Member" behavioral semantics confirmed precisely; Cluster-Mode-string non-correlation already documented (sk180184).

### D-V9a — CP VSX sk165432 documented caveat semantics.

- **area**: OP.0b.0 Vendor Failover Preflight Evidence
- **options**: ['Confirm from official vendor documentation']
- **recommendation**: No further action needed for freeze.
- **decide_by**: resolved -- interpretation already frozen
- **decided_on**: 2026-09-03
- **decision**: PARTIAL, but does not block freeze -- the safe fail-closed interpretation (a contradictory non-VS0 cphaprob stat read is UNKNOWN/RELATIONSHIP_INCONSISTENT, never KNOWN_BAD, never a per-VS action input) was already written into this contract at session 1. Affected releases/fix version/official alternative remain unconfirmed but are not load-bearing for that interpretation.

### D-F3 — Numeric flap/failover-frequency threshold for check 7 (flap_history), both vendors -- new 2026-09-03 (session 4), parallel to D-F1/D-F2. Does not block freeze: the qualitative meaning is fixed (exceeding an as-yet-undecided threshold is unsafe; an undecided threshold means check 7 cannot yet compute a real PASS -- fail-closed, never silently permissive).

- **area**: OP.0b.0 Vendor Failover Preflight Evidence
- **options**: ["Fixed conservative default (mirroring op_continuity_tolerance's pattern)", 'Operator-tunable within bounds', 'No threshold, ever -- advisory-exempt (DECIDED 2026-09-05)']
- **recommendation**: Superseded 2026-09-05: no threshold is invented; the check stays honest and permanently non-blocking instead.
- **decide_by**: resolved -- product-owner policy decision, OP.2.1b
- **decided_on**: 2026-09-05
- **decision**: Advisory-exempt, permanently -- no numeric threshold invented (OP.2.1b, 2026-09-05, docs/history/phase/OP_2_1B_CP_PILOT_READINESS_POLICY_AMENDMENT.md). The collected A8/P2 flap/failover counters are cumulative since an operator-triggered reset with no recency/window semantics; the product owner declined to fabricate a threshold against evidence that cannot support one. flap_history stays INSUFFICIENT_EVIDENCE, visible, for both vendors, forever -- but is now a closed-list, exact-reason entry in utils/failover/assessment.ADVISORY_EXEMPT_CHECKS, so it no longer independently blocks a positive readiness verdict.

### op_reversal_model — Reported contradiction inside the design parent, raised per AGENTS.md 'Authority hierarchy' rather than silently reconciled. FAILOVER_ENGINE_ARCHITECTURE.md sections 5/7/8 require automatic rollback on a failed or partial transition (and section 6 defines a FAILED_ROLLED_BACK outcome); section 10.1 items 5-7 require explicit human confirmation per action, exactly one action per authorised run, and UNKNOWN as a first-class outcome that is never a reason to re-issue. An automatic rollback fires precisely when the entity's state is unverified, so it is a second CLASS 2 mutation, unconfirmed, against an unknown state. Which text governs?

- **area**: OP.2 Controlled Failover Execution
- **options**: ['Reversal is a NEW typed CLASS 2 action (own authorization, fresh preflight, confirmation, lock, single submission, verification, audit); automatic rollback and FAILED_ROLLED_BACK are removed from the model', 'Keep automatic rollback as designed in sections 5/7/8 and narrow section 10.1 items 5-7 accordingly']
- **recommendation**: Reversal as a new typed action. The situation an auto-rollback exists to handle is the situation in which the postcondition is unknown, and issuing an unconfirmed second mutation against an unknown state is the worst available action; section 10.1 is also the later, explicitly dated safety contract and is the one the frozen OP.0b.0 CLASS 2 handoff cites. Drafted as principle P12 of docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md (reviewed 2026-09-04).
- **decide_by**: OP.2.0 contract freeze
- **decided_on**: 2026-09-04
- **decision**: Reversal/failback is a NEW typed CLASS 2 action: new authorization, fresh same-workflow preflight, new confirmation, HA-entity lock, one new mutation attempt, independent post-action verification, independent audit record. No automatic rollback for HA failover; FAILED_ROLLED_BACK is not a state. FAILOVER_ENGINE_ARCHITECTURE.md section 10.2 records the supersession of its sections 1-8 auto-rollback wording.

### op_outcome_unknown_recovery — What happens to an operational HA entity after a CLASS 2 action terminates OUTCOME_UNKNOWN (mutation boundary crossed, postcondition not independently determinable -- including the process-crash-after-submission case, which the product cannot distinguish from a transport timeout after submission)? FAILOVER_ENGINE_ARCHITECTURE.md section 10.1 item 7 makes UNKNOWN first-class but does not say what the entity's subsequent state is, and console/jobs.py's existing crash sweep resolves an orphaned running record to 'failed' -- correct for a class 0 collection, and the single most dangerous possible answer for a class 2 action, since it asserts the mutation did not happen from evidence that only proves the process died.

- **area**: OP.2 Controlled Failover Execution
- **options**: ['Quarantine the HA entity against further CLASS 2 actions until an explicit audited operator acknowledgement; class 0 reads stay permitted; terminal state is never rewritten by a later observation', 'Allow a new CLASS 2 action once a fresh preflight returns a positive verdict, with no acknowledgement step']
- **recommendation**: Quarantine until acknowledged. A green readiness verdict answers 'is the entity safe to act on', not 'did my previous action execute', and letting the first silently clear the second is how a double failover happens. A later class 0 observation appends to the action record and never rewrites its terminal state. Drafted as principle P10 of docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md (reviewed 2026-09-04).
- **decide_by**: OP.2.0 contract freeze
- **decided_on**: 2026-09-04
- **decision**: Quarantine until acknowledged. After the mutation boundary is crossed, process death, worker death, transport timeout or a lost response is OUTCOME_UNKNOWN -- never merely FAILED -- unless independent evidence proves a more specific terminal outcome. The entity stays quarantined (a derived predicate over the unacknowledged OUTCOME_UNKNOWN action record, not a lock or entity state) until an explicit, authorized, audited operator acknowledgement; class 0 reads stay permitted; a later observation appends and never rewrites the terminal state. Class 0's sweep_orphaned_running -> failed stays valid for class 0 only; CLASS 2 does not inherit it.

### pcp_console_registry_write_gate — SCOPED 2026-09-05 to the LOCAL controlled loopback console profile only. May the local-loopback Operator Console accept an enrollment write -- manual (endpoint + opaque profile references) or candidate-based (closed candidate_id) -- before DEPLOY.1A? Server/production enrollment exposure is a separate decision (pcp_server_enrollment_exposure).

- **area**: PCP.x Product Control Plane
- **options**: ['(a) neither intent ships from the console before DEPLOY.1A -- both stay CLI-first through PCP.1/PCP.2', '(b) candidate-based enrollment only, permitted pre-DEPLOY.1A on the strength of the closed candidate id plus typed confirmation + audit record; manual entry still waits', '(c) both permitted pre-DEPLOY.1A with typed confirmation + audit record + mandatory strict first-contact trust preflight for any resulting device']
- **recommendation**: No pre-decision for either intent. Recommendation deferred to the security lead's reading of the exclusions precedent at the PCP.4 contract review. CLI-only enrollment in PCP.1/PCP.2 does not depend on this decision and proceeds regardless.
- **decide_by**: DECIDED 2026-09-05 at the M0 architecture freeze (was: PCP.4 contract review)
- **decided_on**: 2026-09-05
- **decision**: DECIDED -- YES for BOTH manual and candidate-based enrollment, in the explicitly controlled loopback runtime profile ONLY, and only under every condition frozen in docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md section 9.1 and CON.0 section 4.1/7.11: closed typed enrollment intent (endpoint, opaque credential-profile reference, opaque trust-profile reference, permitted tags, and/or a closed candidate id) and nothing else; no credential payload, command, argv, filesystem path or arbitrary transport field; no device I/O in the enrollment HTTP request; first contact as a separate queued CLASS 0 read-only job; strict transport trust before credential submission; positive-evidence vendor/identity; UNKNOWN/ambiguous/contradictory identity persists no registry record; operator review of the resolved identity; explicit confirmation; immutable audit durable before registry mutation; the one existing DeviceRegistry enrollment path with duplicate detection and the mutation-lock contract unchanged; NO automatic-write exemption for candidate-based enrollment. The permission is conditioned on the loopback binding itself and does not survive into server mode. This decision authorizes no code: movement M9 implements it.

### pcp_auto_enrollment_policy — Should a future opt-in 'trusted management source auto-enrolls candidates' policy exist, and under what audit/allowlist?

- **area**: PCP.x Product Control Plane
- **options**: ['never -- explicit enrollment only', 'opt-in per management source, audited, allowlisted, default off']
- **recommendation**: Not in the first slices; design only after PCP.2 shows real candidate volume. 'Everything discovered becomes authoritative inventory' is not the default architecture in any case.
- **decide_by**: DECIDED 2026-09-05 at the M0 architecture freeze (was: PCP.2 closure)
- **decided_on**: 2026-09-05
- **decision**: DECIDED FOR THE CURRENT HORIZON -- NO automatic persistent enrollment. No discovery source and no 'trusted management source' policy may automatically create a persistent enrolled device. Manual endpoint enrollment and candidate-based enrollment BOTH require positive identity evidence, operator preview and explicit confirmation. A future auto-enrollment capability is a separately gated capability that requires a NEW Product Owner decision; 'not now' is explicitly not a permanent prohibition, and this row must be reopened (or a successor created) rather than silently reinterpreted.

### pcp_local_control_plane_storage — For the approved LOCAL control-plane sequence, where does new control-plane metadata live, and does the PCP.1 Device Registry move with it?

- **area**: Product Control Plane / local storage sequencing
- **options**: ['A -- registry stays filesystem JSON; SQLite backs only new local control-plane metadata', 'B -- one governed movement migrates registry plus job/control-plane metadata to SQLite', 'C -- no SQLite yet; continue with filesystem concerns for another slice']
- **recommendation**: Option A -- it does not reopen PCP.1's frozen section 21 contract, and puts the new engine where the new query-shaped requirements are.
- **decide_by**: DECIDED 2026-09-05 at the M0 architecture freeze
- **decided_on**: 2026-09-05
- **decision**: DECIDED -- Option A. The PCP.1 Device Registry REMAINS on its frozen filesystem JSON backend. A new SQLite store owns ONLY new local control-plane metadata: job definitions once durable, job/run lifecycle records, schedules, capability projections, idempotency/submission metadata, control-plane runtime metadata. It owns NONE of: Device Registry rows, credential payloads, trust secrets, raw configuration, backup bytes, CAS evidence objects, OP.2 action authority. The registry stays authoritative at job admission and again immediately before execution; a disabled or unresolvable target causes refusal/abort before contact with the reason recorded, and no copied endpoint is ever retained as fallback authority. THIS IS NOT A PRODUCTION ENGINE SELECTION -- see pcp_storage_engine. A future Device Registry backend migration remains a governed storage movement that must prove semantic parity for normalization, duplicate handling, lifecycle, concurrency, lock/transaction behaviour, corrupt/unsupported-state failure and rollback. This decision authorizes no code: movement M4 implements it under its own contract.

### pcp_first_contact_trust_policy — Must the first-contact job for a MANUALLY enrolled endpoint require strict CP host-key / PAN CA trust even in the local development profile, so that real credentials are never presented to a mistyped or hostile endpoint?

- **area**: PCP.x Product Control Plane
- **options**: ['strict trust mandatory for any endpoint not corroborated by a management-plane candidate', 'existing compat-mode default applies to manual endpoints too']
- **recommendation**: Strict trust mandatory for non-corroborated endpoints; compat mode stays available only for candidate-corroborated endpoints in the dev profile.
- **decide_by**: DECIDED 2026-09-05 at the M0 architecture freeze (was: PCP.2 contract review)
- **decided_on**: 2026-09-05
- **decision**: DECIDED -- strict transport trust is REQUIRED before credentials are submitted to EVERY endpoint, including management-plane candidates. Candidate provenance may supply or select an approved trust profile; it may NOT waive SSH host-key or TLS trust. Prohibited: TOFU, automatic trust acceptance, certificate verification bypass, credential-first probing. The approved Check Point and Palo Alto trust-establishment mechanics are movement M8's implementation contract, over the existing transport seams (utils/cp_ssh_trust.py, utils/pan_tls_trust.py) -- no new credential or network path.

### ui2_phase0_decide_2026_09_09 — UI 2.0 Phase 0 DECIDE: runtime direction, JOB-UNCERTAIN-OUTCOME, UI-OPERATIONAL-RUN-NOW, APPROVAL-MODEL, RAW-RETENTION, FREEZE-SLICING, HISTORY-IMPORT, IN-FLIGHT-LINE-1, DIRECTORY-POSTURE, RESTORE-IN-RELEASE-1, FIRST-CAPABILITY

- **area**: UI2.x
- **options**: ['see docs/design/UI2_0_BASELINE_CONTRACT.md §2']
- **recommendation**: Recorded rulings; DIRECTORY-POSTURE conditional on corporate verification; RAW-RETENTION default NO; HISTORY-IMPORT deferred.
- **decided_on**: 2026-09-09
- **decision**: docs/design/UI2_0_BASELINE_CONTRACT.md (FROZEN -- PRODUCT OWNER APPROVED 2026-09-09)
