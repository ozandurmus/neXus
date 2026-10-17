# PO decision record — 2026-10-18: policy collection completeness (seven new read commands, partial-first, MDS)

**Status: RATIFIED — PO APPROVED (chat, 2026-10-18).** Recorded by the engineering session from the Product Owner's
explicit chat answer, quoted below, after the command table of the plan was put to the Product Owner.

## 1. The Product Owner's words

> "Hepsi ok devam. Belki ilk başta partial deneyebilirsin ama yapabilirsin okuma komutu düz. Guide de bunları söylüyorsa ok."

English rendering: "All of them are OK, go on. Maybe you can try partial at first, but you can do it, a read
command is plain. If the guide says these too, OK."

On the same day the Product Owner decided about the Check Point management server (MDS), which neXus has kept disabled
since 2026-10-16 after two nightly crashes. The wording below is the English text the engineering session was given for
this decision; the Product Owner's original words for it were not part of the material this record was written from:

> "MDS can be enabled; only no backup; other collections may run."

## 2. Interpretation

| Phrase | Meaning recorded here |
|---|---|
| "Hepsi ok devam" (all OK, go on) | The seven new commands of plan §6 are approved exactly as written, each as its own row: P-1, P-2, P-3, P-4, C-1, C-2, C-3. Approving one row still does not approve any other command. |
| "okuma komutu düz" (a read command is plain) | All seven are CLASS_0 reads; none changes a device's configuration or operational state. This is the PO's statement of why the approval needs no further per-command ceremony. |
| "partial deneyebilirsin" (try partial first) | The partial-first rule, section 4. |
| "Guide de bunları söylüyorsa" (if the guide says these too) | The approval holds where the vendor documentation cited in plan §6 supports the call. Where it does not settle a vendor semantic, the semantic stays UNKNOWN (plan §8). A documented parameter is not a documented meaning. |

C-3 (a smaller Check Point NAT page) is approved but **conditional**: its gate row is registered, and no collector uses
it until the Check Point lane shows, from the stored transcripts of the failed NAT reads, that a timeout or size cause
exists (plan §5, step 1b). The retry literal that C-3's retry rule names (`limit 50`) is approved together with C-3 and
is registered as its own gate row only when C-3 is first used.

## 3. Decisions

| # | Decision |
|---|---|
| D1 | P-1 (Palo Alto NAT rule hit counts, firewall), P-2 (Panorama config log, incremental), P-3 (Panorama config log, seed), P-4 (Panorama log job result), C-1 (Check Point `show-changes`), C-2 (Check Point `show-task`) and C-3 (Check Point NAT page amendment) are **APPROVED** exactly as plan §6 writes them: command text, timeout, retry, frequency, session reuse, unsupported behavior, secret-output risk and safe telemetry. |
| D2 | Each approved command becomes a `SIGNED_OFF` read row in `gate_registry` (migration V163), with command text byte-identical to the plan. P-2 carries the same text as the approved firewall row `pan_syslog_collect_config_enqueue`; it is a different canonical key only by `platform_role_scope` (`panorama`), as `UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §3.3 requires. |
| D3 | The MDS may be **enabled** in neXus. Check Point **backup of the MDS stays off**. Every other collection against it (discovery, policy and the readers of D1) may run. Enabling the MDS row is an operational action by the Product Owner or by an engineering session on request; this record and its lane change no device state. |
| D4 | The plan's recommendation to land the Check Point changed-only scheduling (plan §5 step 4a) before or together with re-enabling the MDS is **not** made a condition by this record. It remains an engineering recommendation: the automatic Check Point run is the heaviest MDS read neXus makes, and whether policy collection relates to the crashes is UNKNOWN. |
| D5 | The partial-first rule of section 4. |

## 4. The partial-first rule

Every new reader is first tried **partially**: **one device or one domain, one call**. Concretely, for the first use of
each approved command:

- P-2, P-3, P-4: one Panorama.
- C-1, C-2: one Check Point domain.
- P-1: one firewall and one VSYS, one call. The `nat` element of P-1 is a substitution into the documented security
  form (plan §6, P-1 field 1), so this first call is the measurement the plan asks for before any production use.

Each reader is **off by default** and is switched on by a setting that names its one trial target. Widening a reader
beyond its trial target is a separate decision after the partial result has been reviewed; this record does not make it.
Running is window-gated by `JobWindowPolicy` (00/06/12/18 Europe/Istanbul) and admitted like the existing policy jobs.

## 5. What is not decided here

- Every UNKNOWN in plan §8 stays UNKNOWN: the exact XML for NAT hit counts; Panorama config-log field content for
  device-group changes; any API rate limit; the NAT response size per VSYS; the Check Point maximum `limit` and the
  default `show-hits` window; whether `show-changes` opens a session or lock on the MDS; whether a modified rule's diff
  names its layer; the exact R81.10/R81.20 API version; whether policy collection load relates to the MDS crashes.
- Plan steps 1 to 4 (the diagnose-and-fix work, the object types, the NAT parse scope, the changed-only scheduling and
  the revision tables beyond what V163 holds) are not approved by this record. The PAN NAT parse-scope acknowledgement
  (plan §7 question 2) and the other open questions of plan §7 are not answered by it.
- Device-command approval under `AGENTS.md` (PO Amendment 2026-09-26) covers the seven commands only, and only through
  neXus code with the sanitized projections the plan names. HOST-A is not a jump host.

## 6. Pointers

- Plan: [POLICY_COLLECTION_COMPLETENESS_PLAN.md](POLICY_COLLECTION_COMPLETENESS_PLAN.md) — §6 (the command table), §8
  (the UNKNOWN register), §9 (the implementation record).
- Earlier records of the same shape:
  [PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md](PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md),
  [PO_DECISION_RECORD_2026_10_01_PAN_POLICY_READS.md](PO_DECISION_RECORD_2026_10_01_PAN_POLICY_READS.md).
