# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

## Operating role for the next session

`roles/ENGINEER.md` carries the reading order. PO assistant: `PO.md`.

## 1. Snapshot

- Branch `claude/inspiring-maxwell-gazhy3`.
- UI2.0 boots: Spring Boot service + PostgreSQL 16 + Flyway V1–V7, serving a
  React 18 / MUI 5 shell built through the real Vite pipeline.
- Product default screen is the empty state (no devices). Design preview of the
  populated Overview and Inventory screens is behind `?preview=overview|inventory`
  and is banner-labelled; a test asserts preview data never reaches the product screen.
- B1-1a/2/3/4/4b FROZEN, B1-5/B1-7 DRAFT. No B1 row has real-environment evidence.
- Standing PO gate: **no vendor data-collection work** until the PO specifies,
  per vendor, collection type and methods. UI2.0 shell work is exempt.
- Standing PO rule: new feature implementation is Java written from scratch; the
  existing Python scripts are know-how only, never reused.

## 2. What changed this session

- Service boots against PostgreSQL with file-based credentials (`DatabaseConfiguration`,
  `MigrationStartupRunner`); `service.api` excluded from the scan with a documented reason.
- V5 audit-redaction policy; V6 fixed my own NULL-check defect that blocked legitimate
  writes; V7 actor-fingerprint index. Integration suite 19 → 86 tests, 0 failures.
- Frontend rebuilt on the PO's design-canvas M3 scheme (`theme/m3Theme.ts`), navigation
  rail, empty state, and the synthetic preview data set (`preview/previewData.ts`).
- Privacy regression I introduced and pushed (address-shaped literals bundled into
  three `dist` copies) found and corrected; gate back to the 3 pre-existing
  runtime-directory findings.
- New tests: `test_action_taxonomy_java_parity.py`, `test_design_cross_references_resolve.py`,
  `test_contract_authority_status.py` (fixed `_classify`).

## 3. Exact next action

Continue the shell, one piece at a time, on the PO's order: **device add** (discovery
vs manual: vendor + IP, Panorama/MDS for discovery), then multi-select import from
discovery results, then login. Phase 1 auth = LDAP + local, local always the fallback;
RADIUS/TACACS later. Write the four failover items into a contract before any probe work.

## 4. Test delta

Frontend 6/6; `ui2/` integration 84 executed / 0 failed; privacy gate 3 findings, all
pre-existing git-ignored runtime directories.

## 5. New risks

- The design-preview screens have not been reviewed against the PO's M3 Configuration
  and M3 Network Inventory canvas frames; a dedicated design-transfer pass is pending.
- `scripts/project_queue.py` cannot write `roadmap.json` `now_next.now`/`current_build`.
- **Open PO decisions:** CP backup async semantics contradiction; LDAP TLS CA bundle
  format/pin; PAN A/A (latent — estate is A/S).
