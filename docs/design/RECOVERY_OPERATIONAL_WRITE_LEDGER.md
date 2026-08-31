# Recovery operational-write ledger — design

**Status:** DESIGN — no code. Prepared as `RB.3b` unblocking prep (2026-08-31).
Companion to `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 6, §9.13, and
`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` decision B4. Consumes the
DEV.3.3 evidence backend (`utils/evidence_backend.py`).

**Module to be built (RB.3b step 2):** `utils/recovery_operational_ledger.py`.

---

## PROJE ÖZETİ (Türkçe)

- **Sorun:** Sözleşme "bir uç noktaya 24 saatte en fazla 1 yedek, kesin kural"
  diyor. Bugünkü koordinatör bunu tutamaz — bellekte, tek süreçte çalışır:
  aynı anda ikinci yedeği engeller ama 10 dakika sonrakini engellemez, süreç
  yeniden başlayınca da her şeyi unutur.
- **Çözüm:** Kalıcı bir "işlem-yazımı defteri". Her `add backup local`
  çalıştırması (uç nokta, komut sınıfı, zaman) diske/veritabanına yazılır.
  Yeni yedek denemesinde önce deftere bakılır; uç nokta 24 saatlik pencerede
  ise cihaza hiç dokunulmadan atlanır.
- **Kritik kural:** Defter okunamıyorsa yedek **çalışmaz** (kapalı-hata).
  "Bugün yedek aldım mı, bilmiyorum" cevabı asla "o zaman bir daha al" olmamalı.

---

## 1. Why this exists

Contracts §7.3 point 6: the 24-hour ceiling on `add backup local` per endpoint
is *"hard-enforced by the admission coordinator, not by convention"*. Today it
cannot be:

`utils.collection_executor.CollectionCoordinator` is **process-local and
in-memory** (architecture §9's own correction states this while correctly
arguing per-endpoint locking does not need DEV.3.2). A per-endpoint lock
prevents two *concurrent* backups. It does **not** prevent a second backup ten
minutes after the first, and a process/container restart discards whatever it
knew.

`add backup local` is an `operational-write`: it consumes a bounded resource
(`/var/log` disk) on a production firewall. "At most once per 24 h" is a
disk-safety control, not a politeness convention, so it needs **durable**
state that survives restarts and is shared across containers when the platform
runs more than one.

## 2. Placement — the evidence plane, not the recovery plane

The ledger lives on the **DEV.3.3 evidence backend**, next to
`restore_readiness.json` and `compliance_history.json`, for the same reasons
contracts §5 puts the readiness record there deliberately:

- it is **derived operational metadata** — it holds `entity_id`, a command
  class, timestamps and outcomes; **no recovery payload, no secret, no backup
  filename**;
- it must be readable and writable **even when the recovery volume is
  unavailable** (the recovery volume is egress-denied and may not be mounted in
  every context; the ceiling still has to be enforced);
- it must be **shared across containers** when `SECURITYEXPERT_EVIDENCE_BACKEND
  =postgres` is configured — which is exactly what the evidence backend
  abstraction already provides for the CAS metadata index, run manifests,
  last-known-good and scheduler state.

It is **not** on the recovery plane: nothing here is encrypted, nothing here is
RMA-grade, and a reader of this file learns only "endpoint X had a backup
attempted at time T", which is already implied by a manifest existing.

## 3. Backend — a fifth evidence-backend concern

`utils/evidence_backend.py` gains one concern, following the four existing ones
verbatim (abstract base + filesystem impl carrying today-equivalent behavior +
opt-in Postgres impl + a `select_*` factory keyed on
`active_evidence_backend_kind()`), and reusing `_ensure_schema`
(transaction-level advisory lock, pooler-safe), `_write_json_atomic`,
`_parse_dt` and `EvidenceBackendError`.

```python
class OperationalWriteLedgerBackend(abc.ABC):
    @abc.abstractmethod
    def append(self, entry: "LedgerEntry") -> None: ...
    @abc.abstractmethod
    def entries_for(self, *, entity_id: str, command_class: str) -> list["LedgerEntry"]:
        """Newest-first. Raises EvidenceBackendError if the store cannot be
        read — callers MUST NOT treat that as 'no entries' (see §5)."""

class FilesystemOperationalWriteLedgerBackend(OperationalWriteLedgerBackend): ...
class PostgresOperationalWriteLedgerBackend(OperationalWriteLedgerBackend): ...

def select_operational_write_ledger_backend(*, state_file: Path) -> OperationalWriteLedgerBackend: ...
```

Selection: `SECURITYEXPERT_EVIDENCE_BACKEND` (`filesystem` default | `postgres`)
and `SECURITYEXPERT_EVIDENCE_POSTGRES_DSN`, identical to the other four. The
DEV.3.3 startup preflight (`verify_evidence_backend_ready`) gains a line that
constructs `PostgresOperationalWriteLedgerBackend(dsn)` so a misconfigured
Postgres deployment fails at startup, not mid-run.

### 3.1 Filesystem layout

`<data_root>/state/recovery_operational_ledger.json`

```json
{
  "schema": "securityexpert-recovery-operational-ledger-v1",
  "updated_at": "2026-08-31T12:00:05Z",
  "entries": [
    {
      "entity_id": "<safe_component>",
      "command_class": "cp_gaia_backup",
      "executed_at": "2026-08-31T11:59:58Z",
      "outcome": "completed",
      "run_id": "<run id | null>"
    }
  ]
}
```

Append = read whole file → append one entry → `_write_json_atomic` (the exact
pattern `data/state/compliance_history.json` already uses). Whole-file writes
are fine: entries are tiny and low-frequency (an `operational-write` per
endpoint is ≤ 1/day by construction).

### 3.2 Postgres schema

```sql
CREATE TABLE IF NOT EXISTS recovery_operational_write_ledger (
    id            BIGSERIAL PRIMARY KEY,
    entity_id     TEXT        NOT NULL,
    command_class TEXT        NOT NULL,
    executed_at   TIMESTAMPTZ NOT NULL,
    outcome       TEXT        NOT NULL,
    run_id        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS recovery_opwrite_ledger_lookup_idx
    ON recovery_operational_write_ledger (entity_id, command_class, executed_at DESC);
```

**Insert-only.** No code path issues `UPDATE` or `DELETE` against this table
(§9.13 test (e)). `append` is one `INSERT`; `entries_for` is one indexed
`SELECT ... ORDER BY executed_at DESC`.

## 4. Module API (`utils/recovery_operational_ledger.py`)

```python
@dataclass(frozen=True)
class LedgerEntry:
    entity_id: str
    command_class: str          # "cp_gaia_backup" today; the class, not the command string
    executed_at: datetime       # tz-aware UTC
    outcome: str                # "completed" | "failed" | "cleanup_failed"
    run_id: str | None = None

class OperationalLedgerUnreadableError(EvidenceBackendError):
    """The ledger exists but cannot be read/parsed, or the Postgres backend is
    unreachable. Fail-closed: the caller MUST NOT run the operational-write."""

class RecoveryOperationalLedger:
    def __init__(self, backend: OperationalWriteLedgerBackend) -> None: ...

    def last_execution(self, *, entity_id: str, command_class: str) -> LedgerEntry | None:
        """Newest entry for the pair, or None when there has genuinely never
        been one. Raises OperationalLedgerUnreadableError if the store cannot
        be read — never conflates 'unreadable' with 'none'."""

    def within_window(self, *, entity_id: str, command_class: str,
                      now: datetime, window: timedelta = timedelta(hours=24)) -> bool:
        last = self.last_execution(entity_id=entity_id, command_class=command_class)
        return last is not None and (now - last.executed_at) < window

    def record_execution(self, *, entity_id: str, command_class: str,
                         executed_at: datetime, outcome: str, run_id: str | None) -> None:
        """Append one entry. Called once per endpoint, AFTER `add backup local`
        was actually sent — success and failure both recorded (§6)."""
```

`command_class` is the **artifact class** (`cp_gaia_backup`), not the literal
device command — the ledger is keyed the way the manifest is, so `cp_mgmt_export`
/ `cp_mds_backup` (RB.3c) reuse it without a schema change.

## 5. Fail-closed contract — the one place a safe degrade would be wrong

`restore_readiness.json` and `compliance_history.json` **degrade to empty** on a
corrupt file — that is correct for them (a missing trend is not a safety
problem). The operational-write ledger is the opposite:

| Ledger state | Meaning | Decision |
|---|---|---|
| **Absent** (file does not exist / table empty) | genuinely no prior `operational-write` for this pair | **proceed** — first backup |
| **Readable, entry inside the 24 h window** | backed up recently | **skip**, zero device contact |
| **Readable, newest entry older than the window** | due | **proceed** |
| **Unreadable** (corrupt JSON, I/O error, Postgres unreachable, query error) | *cannot tell* | **BLOCK** — `OperationalLedgerUnreadableError`; no command sent; endpoint outcome `failed` / reason `operational_ledger_unreadable` |

"I couldn't tell whether I already backed this up today" must resolve to **do
not back it up again**, never to "back it up to be safe" — a false refusal is a
missed backup (recoverable next run); a false proceed is a second
disk-consuming write on a production firewall inside the window the ceiling
exists to hold. The distinction between *absent* (fine) and *unreadable*
(block) is exactly the filesystem backend's existing "missing file → `None`" vs
"parse error → raise" split, made strict on the raise side.

## 6. When `record_execution` is called

- **Called** once per endpoint the moment `add backup local` has been *sent to
  the device* — regardless of whether the fetch, digest verify, store write or
  deletion later succeed. The archive consumed `/var/log` the instant the
  command ran; the 24 h window starts there. `outcome` captures what happened
  (`completed` / `failed` / `cleanup_failed`) so an operator sees why a
  subsequent run was skipped.
- **Not called** when the run aborts *before* `add backup local` — an empty
  credential (D4), an endpoint outside the pilot allowlist, a failed free-space
  precondition (§7.7 → §7.3 point 12), a platform-unsupported endpoint, a VSX
  target. Nothing touched the device, so nothing is recorded and the endpoint
  is due on the next run.

## 7. Ordering vs. admission — the cross-container guarantee

The ledger read and write must both occur **inside** the admission-held section
so that two containers past the window racing the same endpoint are correctly
serialised:

```
run_under_admission(entity_id, op):        # per-endpoint lock (DEV.3.2 pg advisory
    op():                                  #   lock when configured, in-memory else)
        if ledger.within_window(...):  ->  skip, return   # zero device contact
        free_space = read_var_log(...)                     # §7.7
        ... add backup local / fetch / verify / store / delete ...
        ledger.record_execution(...)                       # §7.3 point 6 satisfied
```

Container A acquires the lock, reads the ledger (no recent entry), runs the
backup, writes the entry, releases. Container B then acquires the lock, re-reads
the ledger **inside its own admitted section**, sees A's fresh entry, and skips
with zero SSH. Under a single container the in-memory coordinator serialises the
same way. This is why the check cannot sit before `run_under_admission` — only
the admitted section is mutually exclusive per endpoint.

The per-endpoint lock and the 24 h ledger are complementary, not redundant: the
lock stops *concurrent* backups (including across containers, with DEV.3.2
Postgres); the ledger stops a *sequential* second backup inside the window and
survives restarts.

## 8. Retention of the ledger itself

Append-only, effectively unbounded — rows are a few fields each and accrue at
≤ 1/endpoint/day. No deletion path in `RB.3b`. A later optional compaction may
keep the newest *N* per `(entity_id, command_class)`; it must never remove the
newest entry for a pair (that would reopen the window). Out of scope here.

## 9. Privacy

- `entity_id` is already a `safe_component` (no raw hostname); `command_class`
  is an internal enum; `run_id` is an internal identifier; timestamps and
  `outcome` are value-free.
- **No backup filename, no `/var/log` path, no payload, no secret** ever enters
  the ledger.
- `data/state/*.json` is already covered by the repository privacy gate as a
  never-commit path; the Postgres table lives on the evidence instance already
  classified for identity-bearing data (DEV.3.3 E1).

## 10. Test obligations (contracts §9.13)

Built against both backends (the filesystem default and a real local
PostgreSQL 16, exactly as DEV.3.3's suite runs):

- **(a)** a second `--recovery-collect --recovery-vendor checkpoint` inside the
  24 h window makes **zero** device contact for that endpoint (assert no SSH /
  no `add backup local`), outcome `skipped_recent_backup`;
- **(b)** an unreadable ledger (corrupt JSON on filesystem; unreachable DSN on
  Postgres) → the run is **blocked**, no command sent, outcome `failed` /
  `operational_ledger_unreadable`;
- **(c)** an **absent** ledger (no file / empty table) → the run **proceeds**;
- **(d)** the filesystem and Postgres backends return the **same** skip/proceed
  decision for the same synthetic history;
- **(e)** append-only — a static check / test that no module issues `UPDATE` or
  `DELETE` against the table and the filesystem impl never rewrites an existing
  entry;
- **(f)** the ledger read and write occur **within** the `run_under_admission`
  callable (assert ordering against a spy admission hook);
- **(g)** `record_execution` fires when `add backup local` was sent and does
  **not** fire when the run aborted at or before the free-space precondition.

## 11. Amendment mapping

| Amendment | Target | Change |
|---|---|---|
| **C3** | `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 6 | 24 h ceiling enforced from this durable ledger, read inside admission, unreadable ⇒ fail closed |
| **new §9.13** | `BACKUP_RECOVERY_CONTRACTS.md` §9 | the test obligations in §10 above |
| **§2 note** | `BACKUP_RECOVERY_CONTRACTS.md` §2 | the ledger is an evidence-plane state file, not a recovery-plane object |
| **§9 bullet** | `BACKUP_AND_RECOVERY_ARCHITECTURE.md` §9 | scheduling/coordination gains the durable per-endpoint operational-write ledger |
