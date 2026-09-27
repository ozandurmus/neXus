# Job CLI history -- every job's device conversation, step by step (DRAFT)

**Status:** DRAFT -- for Product Owner decision (questions in §6). Not implementation authority until ratified.

Product Owner, 2026-09-27: "Job history istiyorum. Her backup işi Backbox'ta loglanıyor, CLI logu tutuluyor. Bunun gibi
bir şeyi görmek istiyorum." -- for every job, see what neXus said to the device and what came back, step by step, the
way Backbox shows a backup's CLI trail.

## 1. What exists (measured 2026-09-27)
- `job_step_attempt` (V4) already records one row per device step: `job_id`, `step_index`, `step_kind`
  (e.g. INVENTORY_COLLECT_READ, BACKUP_COLLECT, CONFIRM_PRIMARY_READ), `sent_at`, `outcome` (MATCHED /
  EXPECTATION_UNMET / ...), `output_bytes`, `output_lines`, `fingerprint_sha256`, `error_class`. 3,028 rows today.
- Only the Check Point and Palo Alto job paths write it (cp_/pan_ inventory, configuration, backup, confirm). The newer
  vendor paths (Cisco ASA, FortiGate, FortiManager, Infoblox, Radware, Symantec MC/ProxySG, Pulse Secure) write none.
- It does not record WHICH command was sent, nor how long the step took, nor any of the answer's text.
- Operations › Jobs shows a job's state and result reason, not its steps.

## 2. Proposal
### 2.1 One step row per device command, for every vendor
Extend `job_step_attempt` (migration) with:
- `command_key` -- the gate's canonical command key (`gate_registry.canonical_command_key`, e.g. `show configuration`,
  `get system ha status`, `GET /wapi/v<ver>/member`). Never a free-form string; a command without a gate row cannot be
  sent anyway, so every step has one. Parameters that are identities (VS ids, VDOM names, member uuids) are not stored.
- `finished_at` (duration = finished_at - sent_at).
- `context` -- a closed vocabulary: `expert`, `clish`, `vsenv <n>` (number only), `config global`, `vdom`, `https`.
Every executor (all vendors) writes one row per command through one small helper in the worker, the same helper the
Check Point/Palo Alto paths already use, so the history is complete and uniform.

### 2.2 What is shown for each step (the "CLI log")
A job's detail view (Operations › Jobs › a row → "Steps", and the device's Activity tab) shows, per step:
`#`, time, context, command, duration, outcome, output size (bytes/lines), and -- per §3 -- the output excerpt.
Failed steps are highlighted and show the error class.

### 2.3 What is NOT stored
Raw device output is not persisted by the history (AGENTS.md raw-evidence law). Where the output is already stored as
an encrypted artefact (backups, configuration raw artefact), the step links to that artefact instead (existing RBAC:
administrators can download it; aiview cannot).

## 3. The output excerpt -- the one real decision
Backbox shows the full CLI text. neXus's laws forbid persisting raw output without an explicit evidence contract.
Options for the PO:
- **A (proposed default): no text, only facts.** Command, context, timing, outcome, sizes, fingerprint, error class,
  and a link to the encrypted artefact where one exists. Zero new secret-bearing storage.
- **B: masked excerpt for failed steps only.** The first 20 lines of the answer of a FAILED step, passed through the
  same secret withholding the configuration processors use (passwords/keys/communities/certificates → `[withheld]`)
  and the aiview masking (addresses, names). Stored encrypted with the job, kept 30 days. Helps diagnose failures
  without logging into the device.
- **C: masked excerpt for every step.** Same as B for all steps. Larger store, closest to Backbox; highest leak risk.

## 4. Retention
Proposed: step rows kept as long as their job (jobs are not deleted today); excerpts (if B/C) 30 days.

## 5. Delivery (after ratification)
1. Migration + helper + all vendor executors write steps (worker).
2. Service endpoint `GET /jobs/{id}/steps` (masked for aiview like other responses).
3. Frontend: Steps view in Operations › Jobs and in the device's Activity.
Each is one Codex task, reviewed and deployed one at a time.

## 6. Questions for the Product Owner
1. Excerpt option: A, B or C?
2. If B/C: 30 days retention acceptable? Who may see excerpts (security_admin only, or also aiview masked)?
3. Should the history also list steps neXus decided NOT to send (refused before contact: missing gate, wrong role),
   so a refused job shows why it never reached the device?
