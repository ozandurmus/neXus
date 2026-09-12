# UI 2.0 — agent frozen-contract audit

## Status

**DRAFT — FOR PRODUCT OWNER REVIEW, 2026-09-12.** This document freezes,
amends, supersedes and corrects nothing. It is level-6 evidence under
`AGENTS.md` "Authority hierarchy" — a report, never an authority — produced
under `docs/design/PO_DECISION_RECORD_2026_09_12.md` (FROZEN — PRODUCT OWNER
DIRECTIVES, 2026-09-12) §6, which states: *"a FROZEN status applied by an
agent is not evidence of Product Owner review... do not trust it just
because you froze it."* Every row below audits one item from that section's
6.1 or 6.2 tables. No FROZEN status is applied, removed or proposed here.

## 0. Method and scope

Each finding cites the audited document's own text (quoted where the claim
is about what the document says) and, where applicable, a second repository
file that corroborates or contradicts it. Verdicts use exactly one of:

- **SOUND** — the status line's claim is internally consistent with the
  document's own text and with the corroborating evidence read for this
  audit.
- **NEEDS_PO_REVIEW** — the claim is not obviously wrong, but rests on a
  self-reported gap, an unresolved citation, or a cross-file claim this
  movement's read scope cannot verify, and Product Owner judgement is the
  correct next step, not a repair inside this document.
- **DEFECTIVE** — this audit found a concrete internal contradiction between
  two clauses this movement was able to read directly.
- **UNKNOWN** — the evidence read for this audit does not settle the
  question, per `AGENTS.md` "UNKNOWN / fail-closed law".

Read scope for this audit, per `.nexus/WORKER.md`: `PO_DECISION_RECORD_
2026_09_12.md` §6; `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`;
`UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md`;
`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`;
`UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md`; the B1-2/3/4/4b/7 contracts
(`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md`, `UI2_0_B1_03_IDENTITY_SESSIONS_
CONTRACT.md`, `UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md`,
`UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`,
`UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md`); the D1 documents (`UI2_0_D1_DEVICE_
WRITE_CLASS_AND_STEP_KIND_DECISION.md`, `UI2_0_D1_OPTION_A_AMENDMENT_
PROPOSAL_BUNDLE.md`, `UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md`);
`tests/test_contract_authority_status.py`;
`tests/test_design_cross_references_resolve.py`;
`tests/test_project_files_budget.py`. Documents named only as citation
targets inside those files (`C1`, `C2`, `C4`, `C7`,
`UI2_0_ARCHITECTURE_CONTRACT.md`, `UI2_0_ARCHITECTURE_DESIGN.md`,
`UI2_0_BASELINE_CONTRACT.md`, `M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`)
were **not** opened; any claim resting on their content is marked `UNKNOWN`
rather than inferred, per this movement's file-read restriction.

## 1. Section 6.1 — contracts the agent froze

| Document | Who applied the status | What the status line claims today | Verdict |
| --- | --- | --- | --- |
| `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` | The agent, per PO §6.1: "Written and frozen by the agent." | `## Status`: **"FROZEN — 2026-09-12, under the Product Owner's standing written authorization to approve, revise or cancel UI 2.0 B1 contracts."** It states it "Supersedes `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` (DRAFT, never frozen)... four of its load-bearing claims were disproved by the implementation (§10 below records each)." | **NEEDS_PO_REVIEW.** The document's own §10 backs the four-claims-disproved statement with concrete, checkable items (frontend is not a Gradle subproject; the single `AuditContextIntegrationTest` claim; the Docker build command; a fourth privacy-check item) and §3's DIR-1–DIR-10 table (see §4 below) is internally coherent and honestly scoped. Nothing read in this audit contradicts the freeze. It is `NEEDS_PO_REVIEW` rather than `SOUND` only because the freeze was self-applied under a standing authorization the PO has now said is not itself evidence of review (PO §6, epigraph) — the content is sound by this audit's reading, but PO §6 asks explicitly that the freeze act, not merely the content, be reviewed. |
| `UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` | The agent, per PO §6.1: "Written and frozen by the agent, then amended twice by the agent (A-1: `row_pk` is outside the guarantee; A-2: §5.4's stated check was wrong)." | `## Status`: **"FROZEN — 2026-09-12, under the Product Owner's standing written authorization to approve, revise or cancel UI 2.0 B1 contracts."** Scope-limited successor to `UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` §3.5. | **NEEDS_PO_REVIEW**, same reasoning as the row above. §8 "Amendment A-1" and §9 "Amendment A-2" match PO's description exactly: A-1 states "`row_pk` is not covered" by the redaction guarantee and names all seven redacted columns to show none is currently a primary key, backed by a named test (`RedactedColumnIsNeverAPrimaryKeyTest`); A-2 states the §5.4 fail-closed check "compar[ed] the whole redacted snapshot against the raw row" and fired incorrectly whenever every redacted column of a row is `NULL`, and reports it was found against a real PostgreSQL 16 database, not inferred. Both amendments are evidenced and self-critical rather than merely asserted. Content reads `SOUND`; the self-applied freeze act is what PO §6 asks reviewed, hence `NEEDS_PO_REVIEW` for the status act itself. |

## 2. Section 6.2 — contracts the agent amended or corrected

| Document | Who applied the status | What the status line / correction claims today | Verdict |
| --- | --- | --- | --- |
| `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` | The agent, per PO §6.2: "Correction C-1 (session takeover writes two correlated audit rows) and Correction C-2 (§5's auth phasing)." | `## Status` line is unchanged from **2026-09-09** ("FROZEN — PRODUCT OWNER APPROVED, 2026-09-09"); the corrections are appended sections, not a status-line rewrite. The document's own heading for the first correction is **"## Correction C-1 (2026-09-12) — a DRAFT document stood in §1.4's authority chain,"** not the takeover/audit-row description PO's table gives it. | **DEFECTIVE.** Two independent problems, both evidenced below (§3 of this audit). First, PO §6.2's one-line description of "Correction C-1" ("session takeover writes two correlated audit rows") does not match that correction's own title or body (the DRAFT-authority-chain defect); the two-audit-row description instead matches a passing cross-reference made *inside* Correction C-2 (quoted in §3). Second, and separately, that cross-reference is itself false: `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §7 test item 2 ("Takeover, precisely") specifies **"exactly one new `audit_log` row"** on takeover, not two. See §3 for the full quotation and citation trail. |
| `UI2_0_D1_*` (`UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`) | The agent, per PO §6.2: "Pending amendment applied at Product Owner instruction." | `## Status`: **"FROZEN — OPTION A SELECTED AND THE BASELINE AMENDMENT APPLIED, 2026-09-12."** States the amendment "is now applied in full across every document it named," naming `utils/action_taxonomy.py`, `UI2_0_C4_...` §2.3/§3.3, `UI2_0_C7_...` §5.3/§9.1–9.3, and `UI2_0_C2_...` §5.3/§6 as the targets. | **NEEDS_PO_REVIEW.** The claim of PO selection and instruction is stated plainly and matches `UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`'s own status line ("FROZEN — PRODUCT OWNER APPROVED AMENDMENT CONTRACT, APPLIED 2026-09-12"), which is internally consistent with it. Whether `utils/action_taxonomy.py`, `C4`, `C7` and `C2` actually carry the named edits is **UNKNOWN to this audit**: those files are outside this movement's read scope (`.nexus/WORKER.md` "Files you must not touch" / read list omits them), so the cross-file application claim is reported, not verified. `tests/test_contract_authority_status.py`'s own docstring (line 41-48, read as part of this audit's validation-command scope) independently corroborates that the three D1 entries in its `_KNOWN_DRAFT_AUTHORITY_CITATIONS` set "were closed on 2026-09-12 and their entries deleted," for the same reason D1 states — a second-source match for the freeze act itself, not for the underlying C4/C7/C2 edits. |
| `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` | The agent, per PO §6.2: "Marked SUPERSEDED by the agent; body and amendments retained as history." | `## Status`: **"SUPERSEDED — 2026-09-12, by `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` (FROZEN 2026-09-12)."** States "This document was never frozen. It is historical only and is never implementation authority... Any engineering claim that needs one of those must cite the successor instead." | **SOUND.** The claim matches the successor: `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`'s own `## Status` independently states it "Supersedes `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` (DRAFT, never frozen)," a second, independently-worded source for the same fact from the other side of the relationship. Both status lines agree, and `tests/test_contract_authority_status.py`'s own scope note (§7's "Pre-existing findings") confirms the predecessor was cited as authority sixteen times before the successor froze — consistent with a genuine, needed supersession rather than a cosmetic relabelling. |
| B1-2/3/4/4b/7 (`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md`, `UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md`, `UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md`, `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`, `UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md`) | The agent, per PO §6.2: "Sixteen citations repointed clause-by-clause to B1-1a. One citation... was flagged, not repointed." | Each of the five documents carries citations of `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` (module map, dependency direction, build/CI clauses) mixed with cross-references and provenance notes. A raw count of the string `B1-1a`/`B1_01A_PLATFORM_SKELETON` across the five files in this audit's read scope found 4 + 7 + 2 + 2 + 3 = 18 occurrences, not sixteen. | **UNKNOWN** for the exact count. PO's "sixteen" is a count of **citations that needed repointing** (a specific clause-level action), not of every string occurrence of the successor's name in these five files — the 18 raw hits include headings, cross-reference lists and prose that are not citation-repoints and are excluded from PO's count by definition, and this audit did not re-derive PO's own enumeration method within budget. The one flagged, not-repointed citation (B1-2 §7 item 4, `AuditContextIntegrationTest`) is verified directly in §3 below and its own row is `NEEDS_PO_REVIEW` there. The general repointing pattern found while reading (e.g. `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §10 item 2: "B1-2 §7 owns four separate tests") is consistent with PO's description and is not contradicted by anything read. |

## 3. AC-3 deep dive — the B1-2 §7 item 4 citation of `AuditContextIntegrationTest`

**Exact current text**, `UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` §7 item 4:

> **`AuditContextMissingFailsClosedTest`** — behaviour derived from the
> superseded `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §4
> `AuditContextIntegrationTest` spec. **That single-test name is withdrawn
> predecessor material**: `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5
> (final paragraph) and §10 item 2 withdraw it and rule that this §7 owns
> the audit test set. No successor clause replaces it; the citation is kept
> only to record provenance and is not authority.

**Clauses that could serve as a successor**, checked directly:

- `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5, final paragraph: *"The
  predecessor §4 additionally mandated a single test named
  `AuditContextIntegrationTest`. No such test exists: the frozen B1-2 §7
  decomposed that behaviour into `AuditContextMissingFailsClosedTest`,
  `AuditAtomicityTest`, `AuditCoverageCompletenessTest` and
  `DirectAuditLogWriteDeniedTest`. **B1-2 §7 wins**; the single-test name is
  withdrawn and no acceptance check may reference it."*
- `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §10 item 2: *"A single
  `AuditContextIntegrationTest` proves the audit behaviour — false; B1-2 §7
  owns four separate tests."*

**Verdict: NEEDS_PO_REVIEW, not UNKNOWN and not DEFECTIVE.** A functional
successor exists and is directly readable: B1-1a §5/§10 name the four B1-2
§7 tests (items 4-7 of that same section, including the one containing the
citation itself) as the decomposition that replaces the single withdrawn
test name. The phrase "No successor clause replaces it" in B1-2 §7 item 4 is
therefore not a claim that no successor exists — B1-1a §5/§10 function as
exactly that — it is a narrower claim that no clause was written to formally
retarget *this specific citation string* the way the sixteen other citations
in §6.2 were retargeted. Read literally, B1-2 §7 item 4's own sentence is
accurate on its own narrow terms (there is no clause that says "this
citation now points at clause X") but is easy to misread as "no successor
exists at all," which is false by B1-1a §5/§10. This is a wording risk, not
a contradiction this audit can resolve by itself: whether the citation
should be edited to point at B1-1a §5/§10 explicitly, or left as
provenance-only prose, is a Product Owner call, not a repair for this
document.

## 4. AC-4 deep dive — B1-1a §3, `DIR-1` through `DIR-10`

**Exact table**, `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §3:

> | Rules | Proved by | Therefore proves |
> | --- | --- | --- |
> | `DIR-1`–`DIR-7`, `DIR-9` | ArchUnit `ClassFileImporter` over compiled
> bytecode of every production module on the test classpath | the edge is
> absent in the code that actually runs, not merely absent from a build
> file |
> | `DIR-8`, `DIR-10` | direct file-tree inspection of the frontend
> workspace and the wider `ui2/` manifest and source tree — their subject
> is not JVM bytecode | the **repository/manifest half** only |

So per the contract's own text: **DIR-1, DIR-2, DIR-3, DIR-4, DIR-5, DIR-6,
DIR-7 and DIR-9** are bytecode-proved (ArchUnit `ClassFileImporter` over
compiled bytecode); **DIR-8 and DIR-10** rest on file-tree inspection only.

**Evidence gap for DIR-8 and DIR-10**, quoted directly, same section:

> "The image half of `DIR-8` and `DIR-10` — a Python base-image layer, a
> container command invoking Python, an image filesystem carrying Line-1
> paths — **is not proved by anything today**, because no image exists
> (§6). The implementation states that in its Javadoc rather than skipping
> silently, which is the behaviour this contract requires.
>
> So a green `architectureTest` proves `DIR-1`–`DIR-7`, `DIR-9`, and the
> repository halves of `DIR-8`/`DIR-10`. It does not prove the image
> halves, and no acceptance check or CI status may be read as proving them
> until the image contract of §6 freezes."

**Verdict: SOUND.** The contract names its own evidence gap explicitly
(no container image exists, so the image-filesystem half of DIR-8/DIR-10 is
unproved) rather than claiming full coverage, and ties the gap to a named
future contract (§6, the image contract) rather than leaving it open-ended.
This matches PO §6.1's own summary verbatim ("DIR-8 and DIR-10 are
file-tree inspection covering only the repository/manifest half").

## 5. Cross-references

- `docs/design/PO_DECISION_RECORD_2026_09_12.md` §6 — audit authority for
  this document.
- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`
- `docs/design/UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md`
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
- `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md`
- `docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md`
- `docs/design/UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md`
- `docs/design/UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md`
- `docs/design/UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`
- `docs/design/UI2_0_B1_07_JOBS_SCREEN_CONTRACT.md`
- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`
- `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`
- `docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md`
- `tests/test_contract_authority_status.py`
