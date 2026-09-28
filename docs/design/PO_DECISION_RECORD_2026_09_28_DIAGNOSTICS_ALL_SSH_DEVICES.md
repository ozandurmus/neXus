# PO decision record 2026-09-28: the Diagnostics menu serves every SSH-reachable device

**Status:** RATIFIED -- Product Owner, 2026-09-28 (chat): "Diagnostic menüsü ssh erişimi olan tüm cihazlar için
kullanılmalı."

## Context
Operations › Diagnostics ("Debug / Parser") runs one read on one device and keeps the masked output in history. Until
now it accepted only ten exact commands for FortiManager, FortiGate and Cisco ASA (`DiagnosticRead.APPROVED`), and
only `security_admin` may run it. The PO needed to run Quantum Spark identity reads (`show diag`,
`show software-version`) and found the menu refused them.

## Decision (as refined by the PO the same day)
PO: "Tüm SSH yetkili cihazlar; izin verilenlerde bizim veri toplarken kullandıklarımız olacak. Şu ana kadar kod
dizisinde neleri kullandık, onlar safe komutlar. Ek istersen sen bana sorarsın, ben ok dersem oraya ekleriz."
1. Every enrolled device neXus reaches over SSH (any vendor, any role) is a Diagnostics target.
2. **Allowed commands = the read commands neXus already sends while collecting**: every `gate_registry` row that is
   SIGNED_OFF, action class `read`, transport SSH, for that device's vendor / platform scope / shell (templates with a
   `<parameter>` accept one safe token `[A-Za-z0-9_.-]{1,31}`, as today). Nothing else is accepted -- no free text,
   no verb prefixes. Write-class rows (backup submits, failover `clusterXL_admin`, `backup settings ...`) are never
   offered, even though they are gated.
3. **Additions:** the engineering session asks the PO for each new command; on the PO's "ok" it is added as a gate row
   (read, SIGNED_OFF, by migration with the PO's words in `source_document_pointer`) and appears in the menu.
4. The picker shows the allowed commands for the chosen device (a list, not a free text box). `security_admin` runs;
   other roles see history and masked output; output masked (aiview) and secret-bearing lines redacted; one run at a
   time per device; the gate row's own timeout; audit row with actor, device, gate id and exact command.
