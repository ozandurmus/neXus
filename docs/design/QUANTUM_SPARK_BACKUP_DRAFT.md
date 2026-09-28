# Check Point Quantum Spark (Gaia Embedded) configuration backup

**Status:** DRAFT -- DO NOT FREEZE. Awaiting the Product Owner's command approval (§5) and the §4 measurement.
PO direction 2026-09-28: "7 go almamız lazım" (Quantum Spark backup: bring it to a go/no-go).

## 1. Today (measured 2026-09-28, aiview)
- 4 enrolled Check Point gateways are Quantum Spark appliances (no model/version in inventory; inventory collection
  completes). Their `cp_gateway_backup` is refused before any backup command:
  `unsupported_platform: this appliance runs Gaia Embedded (Quantum Spark) ... not built yet`.
- The Gaia method (`add backup local` + `show backup status` + SFTP fetch) cannot be reused: Spark lands in clish,
  `clish -c` only works from Expert, and "SCP to the appliance is supported but you need to enable direct login to the
  Expert mode ... SFTP ... is not supported" (CLI guide p.64, sk52763). Enabling `bashUser on` is a lasting account
  change and a security downgrade -- rejected.

## 2. Vendor documentation (official; research 2026-09-28)
- R81.10.X CLI guide: https://sc1.checkpoint.com/documents/SMB_R81.10.X/CLI/EN/CP_R81.10.X_1500_1600_1800_1900_2000_Appliances_CLI_Guide.pdf
- R81.10.X Locally Managed Admin guide: https://sc1.checkpoint.com/documents/SMB_R81.10.X/AdminGuides_Locally_Managed/EN/CP_R81.10.X_Quantum_Spark_1500_1600_1800_1900_2000_Appliances_Locally_Managed_AdminGuide.pdf
- `backup settings` destinations: R80.20.20 usb/tftp; R80.20.50 usb/tftp/sftp/scp/flash; R81.10.X and R82.00.X
  usb/tftp/sftp/scp (no flash). R81.10.X syntax (p.1938):
  `backup settings to {usb | tftp server <addr> | sftp server <addr> | scp <addr>} [filename <f>]
  [file-encryption {off | on password <p>}] [backup-policy {on | off}] [add-comment "<c>"]
  [username <u> password <p>]`. Server and encryption passwords are on the command line; no prompt is documented.
- `show backup-settings-log`, `show backup-settings-info`, `show periodic-backup` (pp.1940-1945).
- Content (Admin guide p.161): system settings, the SIC certificate and the license; the policy only with
  `backup-policy on`; optional password encryption; `.zip`. Treat as highly secret (likely admin hashes, VPN keys).
- Restore: `restore settings from {usb | tftp | sftp ...} filename <f>` (p.1947), reboots the appliance; no SCP restore.
- `show configuration` / `load configuration` are not in the Spark CLI guide: UNKNOWN on Spark.

## 3. Proposed method: device pushes over SFTP to HOST-A's receiver
Same model as the Radware Cyber Controller (OS-3a): a chrooted, SFTP-only receiver account on HOST-A
(`nexus-spark`, `internal-sftp`, one upload directory, only the Spark management addresses allowed by the sshd Match
rule and the `sftp_push_allowed` nftables set). The worker, in the device's clish session:
1. `backup settings to sftp server <HOST-A> filename <token> file-encryption on password <per-run random>
   backup-policy on username nexus-spark password <receiver secret>`
2. waits for the upload in the inbox, stores it encrypted in the artefact store with the per-run encryption password
   sealed alongside (needed to restore), deletes the upload;
3. `show backup-settings-log` to record the device's own result line.
SFTP over TFTP/SCP: authenticated and encrypted, and it is what HOST-A's receiver already speaks (`internal-sftp`
refuses legacy scp).

## 4. Measurement before freezing (one Spark appliance, PO picks it)
Read-only first: `show backup-settings-log`, `show periodic-backup` (is a periodic backup already configured, and to
where?). Then one `backup settings to sftp ...` run to measure: accepted on the estate's version; exact success output;
duration and size; whether either password is echoed or kept in clish history / logs; whether a temporary file stays
on flash; which SSH algorithms the appliance's client offers and whether it checks our host key.
Record the exact commands and the sanitized output shape next to this file before freezing.

## 5. What the Product Owner approves (gate rows)
| Command (clish) | Class | Timeout / retry | Frequency | Secret-output risk |
| --- | --- | --- | --- | --- |
| `show backup-settings-log` | read | 30 s / 2 | per backup run | low-medium (server name/user) |
| `show periodic-backup` | read | 30 s / 1 | measurement only | medium (may show server credentials): parsed to present/absent only, never stored |
| `backup settings to sftp server ... file-encryption on ... backup-policy on username ... password ...` | CLASS_0 read in effect (outbound copy + a device log line; no configuration change) -- PO to confirm | 180 s / none (never retried in the same run) | nightly with the fleet backup | high: two secrets on the command line -- redacted from transcripts, never in job reasons |
Plus the host side: new `nexus-spark` receiver account and Match rule, Spark addresses in `sftp_push_allowed`.

## 6. Open risk
The Cyber Controller push into the same kind of receiver currently fails (client closes after the `none` auth probe;
cause under investigation). The Spark receiver must not be built until that cause is known.
