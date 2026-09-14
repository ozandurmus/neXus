# PO Decision Record — 2026-09-14 B — The enrollment confirm is the one job admissible against a DRAFT device

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-14.** Successor clause to
`UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §3 and to
`UI2_0_B1_ADJUDICATION_2026_09_12.md` F4 (job admission refuses a `DRAFT`
target; `C2` §6 check 5 re-checks at claim time), both FROZEN and not edited
in place. It records how a contradiction found by movement `NXS-LOCAL-0157`
on 2026-09-14 is resolved, under the Product Owner's standing approval of the
first-contact design (`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` FROZEN
2026-09-14, EC-12; `DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md` approved
the same day). The Product Owner assistant made the call on that approval and
reports it here so the Product Owner can veto it; it does not widen anything
else.

## 1. The contradiction, as found

`B1_04B` §3 and F4 fix that a `DRAFT` device is never collected from and no
job may be created against it — enforced at four merged, tested points
(`JobAdmissionService`, `DeviceEnrollmentState.permitsReadCollection`,
`StepExecutor` at claim time, `DeviceStatePort`). The same §3 makes the
`DRAFT → ENROLLED` transition an *authorized confirm* that contacts the
device. `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` EC-12 (decided
2026-09-13) then fixed that the confirm's device steps run as a `C4` read
capability — and `C4` capabilities run as `C2` jobs. Read together, the
confirm needs a job against a `DRAFT` device, which F4 refuses. Neither side
is wrong: F4 protects a device that has never been confirmed from *collection*;
EC-12 keeps the confirm from becoming a second execution path. The gap is
that "job" was used for both.

## 2. The decision

- **EC-J1. Exactly one job kind is admissible against a `DRAFT` device: the
  enrollment confirm.** Its capability is the confirm capability and nothing
  else — the closed command set of `DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md`
  (four class-0 reads, one peer hop), enforced by the existing closed-set test.
- **EC-J2. Every other job kind keeps F4's refusal, unchanged, at every one
  of the four points.** `permitsReadCollection()` stays false for `DRAFT`;
  admission and claim-time checks now distinguish the confirm kind from
  collection and refuse collection exactly as before. A test proves both: the
  confirm is admitted against `DRAFT`; a collection job against `DRAFT` is
  still `DEVICE_NOT_ELIGIBLE`.
- **EC-J3. The confirm job is what moves the row.** Its completion handler
  lands `ENROLLED` with the observed facts (EC-6a: also on an identity
  mismatch, warn + audit + continue; strict posture switch honoured), or
  leaves `DRAFT` with the failure outcome. No other code path changes
  enrollment state on first contact.
- **EC-J4.** The confirm job obeys every `C2` rule (lease, fencing token,
  heartbeat, per-step timeout, `OUTCOME_UNKNOWN` reconciliation). It is a
  job in full, admitted by `JobAdmissionService`, executed by the worker
  process; it is only *exempt* from the `DRAFT` refusal, and only because it
  is the transition out of `DRAFT`.
- **EC-J5. Peer follow runs inside the same confirm job** (PF-1..PF-5); the
  peer's own row is created `DRAFT` and confirmed by the same job's peer
  steps, never by a second admitted job and never by a collection job.

## 3. What this does not change

`B1_04B` §3's states and every other transition; F4/F6/F7/F12 for every job
that is not the confirm; `C2`; `C4`; `Ui2ArchitectureTest` dir2 (the web
service still never reaches transport code). It authorizes no read beyond the
approved four and lifts no collection gate.

## 4. Cross-references

- `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §3 — the transition
  table this clause completes.
- `UI2_0_B1_ADJUDICATION_2026_09_12.md` F4, F6, F7, F12 — the refusals kept.
- `UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §3, §4, §6 — the job rules the confirm
  obeys in full.
- `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (FROZEN) §4, EC-6a, EC-11,
  EC-12; `DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md` (APPROVED).
- `PO_DECISION_RECORD_2026_09_14_DEVICE_ADD_ENTRY_PEER_FOLLOW_AND_CREDENTIAL_STORE.md` DA-2, PF-1..PF-5.
