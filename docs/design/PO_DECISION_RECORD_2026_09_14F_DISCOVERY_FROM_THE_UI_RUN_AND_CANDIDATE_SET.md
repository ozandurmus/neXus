# PO Decision Record — 2026-09-14 F — Discovery from the UI: the discovery run, its candidate set, and import

## Status

**FROZEN — PRODUCT OWNER DIRECTIVE WITH ASSISTANT DESIGN, 2026-09-14.** The
Product Owner approved the discovery command gates today
(`CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md`,
`PAN_DISCOVERY_API_ROUTE_GATE_ENTRIES.md`, both APPROVED 2026-09-14) and
asked to try discovery from the product in one go. This record completes
`PO_DECISION_RECORD_2026_09_14` DA-3 (the management-server path of the one
add menu) with the pieces the FROZEN contracts leave to "the implementing
movement", and adds one successor clause the contracts need. The Product
Owner assistant made the design calls below under the standing approval and
reports them here for veto.

## 1. Successor clause to the discovery contracts' DI-1

`CP_AND_VSX_DISCOVERY_CONTRACT.md` §8 DI-1 and `PAN_DISCOVERY_CONTRACT.md`
§10 DI-1 (FROZEN) say a discovery run "writes no device row, creates no
persistent record". They were written for a run whose operator sits at the
same terminal. From the UI the operator selects after the run has ended, so:

- **DR-1.** A discovery run is a `C2` job. Its **target is a discovery-run
  row, not a device**: `discovery_run(run_id, vendor, management_address,
  credential_reference_id, requested_by_actor_fingerprint, state, job_id,
  started_at, finished_at, outcome_summary)`. `JobAdmissionService` admits
  the discovery capability against a run row (a new target kind alongside
  devices); every other capability keeps device targets and F4 unchanged.
- **DR-2.** The run's **candidate set is persisted for selection only**:
  `discovery_candidate(candidate_id, run_id, vendor, stable_identifier,
  owning_domain, kind, display_name, own_address, management_address,
  cluster_reference, parent_candidate_id, model, software_version,
  connection_state, importable, import_outcome)` — exactly the candidate-row
  fields the two contracts define (§6 / §4–§7), nothing derived beyond
  `importable` (contract §2 kind table) and, after import, the RD-5 outcome.
  **DI-1's "no device row" holds:** a candidate row is not a device and
  creates no endpoint; only selection creates `devices` rows (DI-2).
- **DR-3. Retention.** Candidate rows live until the run is imported or
  withdrawn, and at most 24 hours; a scheduled or on-read sweep deletes
  expired runs' candidates. Raw responses are never stored (T-7).
- **DR-4.** The management server itself is **not** a device row. The
  run row carries its address and credential reference; a later record may
  promote management servers to first-class rows for Panorama `target=`
  reads and configuration provenance.

## 2. Import (fixes what the import contract left open)

- **IM-9 value:** `registration_source = 'discovery_import'`.
- **Import is the existing per-device unit:** for each selected importable
  candidate the service runs the same register-DRAFT-then-admit-confirm
  step `POST /devices/add-single` uses, with the endpoint mapping of IM-7
  (Check Point: `ssh_exec` to the candidate's management address; Palo
  Alto: `pan_xml_api` to its own address), the credential reference the
  operator picks for the import (defaulting to the run's), `cluster_member_ref`
  from the candidate's cluster reference, `virtual_system_ref` for a
  virtual-system candidate imported with its host. RD-1: selecting a
  cluster selects its members; RD-5 outcomes (`new` / `already_imported` /
  `conflicting`) are written to `import_outcome` and shown; a match is by
  the vendor stable identifier (RD-2/RD-3) against `devices` recorded
  identity. EC-2: import does not perform the confirm; the confirm job it
  admits does.

## 3. Routes and screen

- `POST /discovery/runs` `{management_address, vendor,
  credential_reference_id}` → 202 `{run_id, job_id}`; gated as a write
  action for the onboarding role.
- `GET /discovery/runs/{run_id}` → run state, job view, `outcome_summary`
  (counts only), and the candidate list with `importable`, cluster
  parent/child structure and `import_outcome`.
- `POST /discovery/runs/{run_id}/import` `{candidate_ids[],
  credential_reference_id?}` → 200 `{results: [{candidate_id, outcome,
  device_id?, job_id?}]}`.
- The one add dialog (DA-1): the "management server (discovery)" toggle
  becomes live; same three fields; on submit the dialog polls the run,
  then shows the candidate table (cluster as parent, SB-9), multi-select,
  Import; imported devices then appear in the inventory list where the
  existing enrollment polling applies.

## 4. Cross-references

- `PO_DECISION_RECORD_2026_09_14` DA-1..DA-3, PF-1..PF-5, CS-1..CS-5.
- `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) §2, §3, §4 —
  implemented as written; IM-9 value fixed here.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` §6, §8; `PAN_DISCOVERY_CONTRACT.md`
  §4–§7, §10 — DI-1 completed by §1 above; every other clause unchanged.
- `UI2_0_C2` (job rules the run obeys), `UI2_0_C4` §4.2.

## Amendment A — 2026-09-23 (Product Owner): managed-estate tree and nightly refresh

- **DR-3 amended.** The latest `FINISHED` run per (vendor, management address) is kept past the 24-hour
  window; every older run is still swept. That run is what the managed-estate tree
  (`GET /devices/{id}/management-tree`, `ManagementTreeService`) shows under an enrolled management server, and a
  failed nightly refresh must not leave the tree empty. Candidate rows still hold no raw response (T-7 unchanged).
- **Nightly refresh.** `DiscoveryRefreshScheduler` re-runs discovery at 01:00 Europe/Istanbul against each enrolled
  management server that already has a finished run, with that run's own address, vendor and credential reference —
  the same gated reads, no new command. A first discovery stays an operator action.
- **DR-4 note.** Management servers are now device rows (`role = management_server`, V21); the Panorama that had
  been enrolled as a gateway was corrected on 2026-09-23 (audited, action `panorama_role_correction_by_po_directive`).
- **"Not an issue here".** A gateway a manager lists but neXus does not enrol can be marked not an issue with a reason
  (`discovery_acknowledgement`, V58, audited, `onboarding_admin`); it is then not counted on the tree or on Overview.
