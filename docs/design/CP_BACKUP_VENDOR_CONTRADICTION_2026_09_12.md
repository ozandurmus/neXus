# Contradiction report — Check Point Gaia backup is asynchronous

## Status

**CONTRADICTION REPORTED, 2026-09-12. NOT RESOLVED HERE.** `AGENTS.md`
"Authority hierarchy" requires that a disagreement between authorities is
reported, never silently reconciled. A FROZEN contract states a vendor
behaviour that official Check Point documentation contradicts. The
engineering session that found this has no authority to amend a frozen
contract on a safety-critical vendor semantic, and the Product Owner's
2026-09-12 authorization covers UI 2.0 B1 contracts, not this.

**Affected capability: `cp_gaia_backup_local`, a class-1 controlled write
on production Check Point devices.**

## 0. Documentation evidence obtained 2026-09-12 — no SK article needed

The open question was recorded as blocked on `sk108902` / `sk100403` /
`sk183833`, which are JavaScript-gated and return an empty body to an
automated fetch. That blocker was wrong: the **public Gaia Administration
Guide** answers two of the three questions outright, and was retrieved and
read directly.

Source pages, fetched and parsed (not summarized):
`https://sc1.checkpoint.com/documents/<VER>/WebAdminGuides/EN/CP_<VER>_Gaia_AdminGuide/Topics-GAG/Backing-Up-and-Restoring-the-System.htm`
for `VER` in `R80.40`, `R81`, `R81.10`, and the same page under the
`.../CP_R81.20_Gaia_AdminGuide/Content/Topics-GAG/...` path for **`R81.20`**
(that release moved the page under `Content/`, which is why a first pass using
the older path pattern wrongly read as "no such page").

**`R81.20` is the version this estate runs, and it was checked specifically.**
All four releases agree verbatim, so this is a stable documented semantic
rather than one release's wording — and, more to the point, the finding is not
being carried across a version boundary by inference. `R81.20` shows the same
worked example, the same `show backup {last-successful | logs | status}`
syntax, the same `show backup status` → `Performing local backup` output, the
same `add backup local [interactive]` signature, and the same single
free-disk sentence about the administrator's own workstation with no multiple
named.

The guide's own worked example, quoted exactly:

```
gaia> add backup local
Creating backup package. Use the command 'show backups' to monitor creation progress.
gaia>
gaia> show backup status
Performing local backup
gaia>
gaia> show backups
backup_gw-8b0891_22_7_2012_14_29.tgz    Sun, Jul 22, 2012    109.73 MB
gaia>
```

### Finding 1 — `add backup local` is asynchronous. CONFIRMED by vendor documentation.

The prompt returns immediately with `Creating backup package.`, and the
vendor's own instruction in that same line is to poll: *"Use the command
`show backups` to monitor creation progress."* A command that blocked until
completion would have nothing to monitor. The documented syntax
`add backup local [interactive]` carries the same implication — the
non-interactive default is the asynchronous one.

This confirms the contradiction as reported: the frozen profile's blocking
assumption is not what the vendor documents.

### Finding 2 — `show backup status` is documented, not invented. This reverses a repository claim.

`UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §1.4 (and `K-3`)
refuse the predecessor architecture document's illustrative CP profile partly
on the grounds that it *"invents `show backup status` polling"*. It does not.
The Gaia Administration Guide documents
`show backup {last-successful | logs | status}` in the same syntax block as
`add backup local`, and its example shows `show backup status` returning
`Performing local backup` while a backup is in flight.

This is an error in the conservative direction — the repository refused a real,
documented command as invented — but it is still an error, and it matters
here: the polling surface the corrected profile needs is exactly the one `K-3`
ruled out. `K-3`'s other objections (directory-listing artefact discovery,
delete-by-discovered-name, engine-owned precondition) are untouched by this
finding and stand.

### Finding 3 — the 3x free-disk rule is NOT in the Administration Guide. Still UNKNOWN.

The guide's only free-disk statement is about exporting a backup to the
administrator's own workstation: *"Make sure you have enough free disk space on
your computer."* It states no multiple, and nothing about free space on the
device before `add backup local`. So the repository's 3x rule has no support in
the Administration Guide and remains unsourced. It must stay labelled
`UNKNOWN` / `ASSERTED` until a source is produced, or be relabelled as a local
safety margin chosen by this project rather than a vendor requirement. Do not
promote it to a documented vendor constraint on the strength of this pass.

### What this does and does not settle

It settles the documentation half of the question, which is what `AGENTS.md`
"Vendor semantics law" requires before freezing a safety-critical semantic. It
does **not** amend anything: `cp_gaia_backup_local` is a class-1 controlled
write on production devices, and the Product Owner's 2026-09-12 authorization
covers UI 2.0 B1 contracts, not this capability. It also does not substitute
for real-environment corroboration on the estate's own version — documentation
and a live device are separate evidence grades.

## 1. What the repository asserts

`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
§2.4 (FROZEN):

> No `poll` step: the real sequence's `add backup local` is a single
> blocking `exec` bounded at 900 s (§7.3 point 4), not a fire-and-poll
> pattern.

C4 §1.4 explicitly rejects the alternative, calling the profile in
`UI2_0_ARCHITECTURE_DESIGN.md` §6.3 — which *did* specify a
`show backup status` poll — "not the shipped profile" (`K-3`).

The claim's own source, `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 4,
carries **no citation**. An audit of vendor claims across the contract set
classified it `ASSERTED`: stated with no evidence at all.

## 2. What Check Point documents

The Gaia Administration Guide's own worked example, R82 and R80.30 alike:

> ```
> MyGW> add backup local
> Creating backup package. Use the command 'show backups' to monitor
> creation progress.
> MyGW>
> ```

The prompt returns immediately and the operator is directed to poll.
`show backup status` is documented for exactly this — "Viewing the status
of the latest backup" — and its worked example returns
`Performing local backup` while the backup is still running.

**The command is asynchronous. The frozen contract says it is blocking.**

Sources: R82 Gaia Administration Guide, *Backing Up and Restoring the
System*; R80.30 Gaia Administration Guide, *Gaia Portal System Backup*;
R81/R82 Gaia Administration Guide, *System Backup* and *Configuring
Scheduled Backups*.

## 3. What breaks if the contract is used as written

1. **Success is recorded before the archive exists.** The `exec` step
   returns as soon as the job is accepted, so a `COMPLETED` verdict proves
   only acceptance. `C7` §2.5's separation of "the job ran" from "the
   artefact exists" is what keeps this from being a silent data-loss bug,
   but the job verdict is still wrong.
2. **The 900-second bound measures nothing.** It times the acceptance of a
   command that returns in milliseconds. A backup that takes twenty minutes
   and a backup that fails after thirty seconds are indistinguishable to
   the timeout.
3. **The fetch step reads a partial or absent file.** `scp_get` runs
   against a path the subsystem may still be writing. A digest computed
   over a partial archive is a valid digest of the wrong bytes.
4. **The cleanup step may delete a live archive.** Deleting the on-device
   copy while generation is in flight is a worse outcome than leaving it.

The architecture design document was right and the capability contract
overrode it on an uncited assertion.

## 4. The rest of the gate sign-off is unproven

The same uncited section, `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 14, is
the entire substance the network-security sign-off rests on. Research
against official documentation could confirm **none** of it:

| Claim in the sign-off | Documentation found |
|---|---|
| Restarts or reloads no Check Point process | **UNKNOWN** — no statement either way. The Multi-Domain `mds_backup` utility documents an opt-in `-s` flag to stop processes, which says nothing about Gaia `backup` |
| Holds no global or database lock | **UNKNOWN** for Gaia. Documented only for `mds_backup` (`-L`), a different command |
| Does not affect cluster state, priority, failover or sync | **UNKNOWN** — no Check Point sentence links backup collection to ClusterXL behaviour at all |
| Is not synced to the standby | **UNKNOWN** for ClusterXL. For Management HA the documentation says the opposite in spirit: collect backups "from all Security Management Servers … at the same time", i.e. per peer, operator-coordinated, not replicated |
| Safe on an active production member | **UNKNOWN** — no guidance found about active members, business hours or maintenance windows |
| No meaningful CPU, I/O or traffic impact | **UNKNOWN** — no performance statement in any backup topic |

Two things *are* documented and the contract has them backwards or
missing:

- **On a management server, open SmartConsole clients stop the backup from
  starting.** "make sure to close all SmartConsole clients. Otherwise,
  backup does not start." The product assumes the backup blocks others; the
  documented direction is the reverse, and it is a precondition the
  capability does not check.
- **A Gaia snapshot and a Gaia backup may not run at the same time.** Not
  modelled anywhere.

## 5. The disk-space rule has no vendor basis

`BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 12 requires free space of three
times the largest prior backup. No Check Point document states any
free-space requirement or multiple for the backup destination. The only
documented space language is operator-configurable retention, and it
carries a **silent failure mode**: "The scheduled backup job stops, if Gaia
cannot meet the configured retention policy. … In this case, Gaia does not
show a notification."

The 3× rule should be recorded as an unproven engineering heuristic, which
is a legitimate thing to have, rather than as a vendor-derived requirement,
which it is not. §7.7 point 12 already calls it interim; the downstream
contracts do not carry that qualification.

## 6. What this report asks the Product Owner to decide

1. **Suspend or continue.** `cp_gaia_backup_local` is a class-1 write whose
   gate sign-off is now known to rest on unproven claims and one
   contradicted claim. Suspending the capability until the profile is
   corrected is the fail-closed reading of `AGENTS.md`.
2. **Correct the profile.** The poll pattern the architecture design
   originally specified matches the documented behaviour. Reinstating it
   means amending a FROZEN contract (`C4` §2.4 and §1.4's `K-3`
   correction), which is a Product Owner act.
3. **Re-run the command gate.** The device-impact answers are UNKNOWN, not
   favourable. Under the vendor-semantics law the gate cannot be frozen on
   them as written. The honest options are to record them as UNKNOWN and
   constrain the capability accordingly, or to obtain the evidence.
4. **Decide how to obtain the missing evidence.** Three SK articles that
   would likely settle the device-impact questions — sk108902, sk100403,
   sk183833 — return HTTP 200 with an empty body to an automated fetch
   because the support portal is JavaScript-gated. A human with a Check
   Point account can read them in a browser in minutes. That is the
   cheapest path to closing most of §4.
5. **Re-label the 3× rule** as an engineering heuristic rather than a
   vendor requirement, and decide whether it stays.

## 7. What this report does not do

It does not amend any contract, does not change any code, and does not
judge whether the underlying command is safe. It reports that the evidence
the repository offers for its safety does not support what the contract
asserts. The capability may well be harmless; the point is that the
repository cannot currently show it.
