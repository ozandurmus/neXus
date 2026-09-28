# PO decision record 2026-09-28: the Diagnostics menu serves every SSH-reachable device

**Status:** RATIFIED -- Product Owner, 2026-09-28 (chat): "Diagnostic menüsü ssh erişimi olan tüm cihazlar için
kullanılmalı."

## Context
Operations › Diagnostics ("Debug / Parser") runs one read on one device and keeps the masked output in history. Until
now it accepted only ten exact commands for FortiManager, FortiGate and Cisco ASA (`DiagnosticRead.APPROVED`), and
only `security_admin` may run it. The PO needed to run Quantum Spark identity reads (`show diag`,
`show software-version`) and found the menu refused them.

## Decision
1. Every enrolled device neXus reaches over SSH (Check Point Gaia and Gaia Embedded, Palo Alto, FortiGate,
   FortiManager, Cisco ASA, and any later SSH vendor) is a Diagnostics target.
2. The command is typed by the operator, not chosen from a fixed list. **The running `security_admin` is the
   approver of that exact command** for that one run: this is how the 2026-09-26 ad hoc diagnostics amendment
   (AGENTS.md, "exact command ... approved before execution") is met for this menu. The audit row carries the actor,
   the device, the exact command and the time.
3. **Read-only guard (engineering condition, PO informed):** a command is admitted only if it starts with a
   read verb of its vendor/shell (per-vendor allowlist in code, e.g. `show`, `get`, `display`, and named read
   commands such as `cphaprob`, `fw stat`, `cpstat`) and contains none of `; | & > < $ \` ` or a newline. Verbs that
   change state are refused before contact (`set`, `delete`, `add`, `clear`, `execute`, `config`, `configure`,
   `request`, `reboot`, `debug`, `diagnose` except named read forms, `test`, `commit`, `save`, `write`, `copy`, ...).
   The allowlist lives in one class with tests; widening it is a code change the PO sees.
4. Unchanged: `security_admin` only runs; other roles see history and masked output; output is masked (aiview) and
   secret-bearing lines are redacted as in the existing panel; one run at a time per device; timeout 30 s; host keys
   trusted; no Browser -> device path beyond this typed intent (the service resolves transport and shell).
