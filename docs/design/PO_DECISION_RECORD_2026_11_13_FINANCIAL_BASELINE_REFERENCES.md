# PO decision record -- 2026-11-13: Financial baseline references are neXus identifiers, not regulation clauses

**Status: RATIFIED -- PO APPROVED ("Temizle", chat, 2026-11-13, relayed by the PO session).**

## Finding

The decision council that reviewed the BDDK-related zone matrix feature on 2026-11-13 found that the Financial baseline
mappings in the compliance catalogs carry clause-like references that were never taken from a regulation, and that the
code linked the set to BDDK:

- every `ComplianceFramework.FINANCIAL_BASELINE` mapping in the Check Point and Palo Alto catalogs (and the Cisco ASA /
  FortiGate catalog, which copies its frameworks from them) used text such as `"Access Control §3.1"` with version
  `"2026"`. The section numbers and the year do not come from any regulation text. An auditor could read them as BDDK
  clauses.
- the Palo Alto catalog described the set as "Financial Baseline (BDDK)".
- `UI2_COMPLIANCE_MICROSERVICE_ARCHITECTURE.md` listed "Financial Baseline / BDDK / COBIT 2019" and showed a sample
  mapping `"Identity & Access §4.1"`.

The earlier compliance reviews (`CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md`, `CLAUDE_COMPLIANCE_ARCHITECTURE_REVIEW.md`,
`CODEX_COMPLIANCE_ARCHITECTURE_REVIEW.md`) had already said that a BDDK claim needs a source of record with article
numbers. None exists.

## Decision

1. The Financial baseline is **a neXus baseline for financial institutions. It is not a mapping to any regulation.**
   The framework, its pass / fail / no-evidence results and its display name ("Financial baseline") stay.
2. Invented clause references are replaced by neutral neXus control identifiers (scheme below). Nothing in code,
   user-visible text, reports or current design documents associates the set with BDDK.
3. Wherever the set is described the statement is: "neXus baseline for financial institutions; not a mapping to any
   regulation. Regulatory mapping is the bank compliance team's input."
4. A regulatory mapping (BDDK or any other) is made only by the bank's compliance team, from a source of record, as a
   separate, later piece of work. Until then the product claims none.

## Identifier scheme

`FB-<AREA>-<NN>`: `FB` = Financial baseline, `AREA` = upper-case control area, `NN` = two-digit sequence inside the
area, starting at 01. The identifier is stable: it is never reused for a different control, and never renumbered. The
mapping `version` is the baseline's own revision, `"1"` (it was the year `"2026"`, which read as a regulation edition).
The report "control family" of a Financial baseline check is `FB-<AREA>` (the identifier without its last part).

| Retired reference | neXus identifier |
|---|---|
| Access Control §3.1 | FB-ACCESS-01 |
| Brute Force Protection §2.4 | FB-LOCKOUT-01 |
| Session Governance §5.2 | FB-SESSION-01 |
| Transport Security §1.1 | FB-TRANSPORT-01 |
| Time Synchronization §4.2 | FB-TIME-01 |
| Audit Logging §6.1 | FB-AUDIT-01 |
| Warning Banners §1.3 | FB-BANNER-01 |
| Cryptographic Baseline §2.1 | FB-CRYPTO-01 |
| Segment Isolation §1.2 | FB-SEGMENT-01 |
| Password Lifecycle §3.3 | FB-PASSWORD-01 |

The mapping is one to one: the same controls map to the baseline as before and several controls may still share one
identifier (as they shared one reference). Only the label changed.

## Stored data

Compliance evaluations are stored as JSON documents (`items[].frameworks[].reference`). **No migration and no write to
the live database.** Evaluations are recomputed on every compliance run; a new evaluation carries the new identifiers
and replaces the stored one.

Until a firewall is re-evaluated, its stored evaluation still carries the retired text:

- Reports (RPT-02 per-family tables): the retired reference is read under the family of its replacement
  (`ComplianceFacts.family`: "Access Control §3.1" is counted in `FB-ACCESS`), so a report never mixes old and new family
  names. An unknown retired reference is counted as `Unmapped`.
- The Compliance screen and its CSV export read the control catalog (new identifiers) for the control list and the
  stored evaluation only for pass / fail status; an old row's reference text is shown as stored until re-evaluation.
- Counts per framework are unaffected: they use the framework key, not the reference.

## Where the statement and the identifiers appear

Compliance catalogs (`CheckPointComplianceCatalog`, `PaloAltoComplianceCatalog`; the Cisco ASA / FortiGate catalog
copies them), `ComplianceFramework.FINANCIAL_BASELINE` description, Reports R1 (RPT-02 framework summary and the
Financial baseline family section notes; PDF, XLSX, CSV and the on-screen panel render the same notes), the Compliance
screen (note under the filters when the Financial baseline is selected), the Overview "Compliance by framework" row
(tooltip), and `UI2_COMPLIANCE_MICROSERVICE_ARCHITECTURE.md`.

## Out of scope

- The Python-era files (`history/`, `docs/history/`, `docs/ARCHITECTURE.md`, `scripts/consult_*`) and the earlier
  design reviews that discuss a BDDK framework: they are frozen history of a different, retired framework
  (`CIS / PCI-DSS / BDDK` in `utils/framework_catalog.py`) or quote the council findings above. They are not the
  neXus ui2 Financial baseline and are left as the record.
- The zone matrix feature: this record only removes the regulatory implication it relied on.

## Tests

`FinancialBaselineReferencesTest` (every Financial baseline mapping in every catalog is `FB-AREA-NN`, no year as an
edition, the framework description carries the statement and names no regulator) and `FinancialBaselineFamilyTest`
(new and retired references read under `FB-*` families; RPT-02 carries the statement).
