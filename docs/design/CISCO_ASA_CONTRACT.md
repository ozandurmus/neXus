# Cisco ASA — confirm, inventory, backup over SSH

**Status:** FROZEN by the Product Owner, 2026-09-25 ("İkisi de olsun, sırayla alır, hata veren kısım eksik gözükür";
SCP is enabled on the estate's ASAs by their administrators). Commands gated in V78 and V79. Delivery status
IMPLEMENTED until the real-environment measurement below.

## Reach
- Vendor `cisco_asa`, role gateway ("Security device"), transport `ssh_exec` on port 22 (or the address's port).
- One interactive shell per run (the path the Spark appliances already use). The account must be privilege 15:
  `show curpriv` is read first and a lower level stops the run with the level named. No `enable` password is ever
  typed by neXus.
- Host key: the same trust path as a Check Point device (first-use record, mismatch warned — PO decision).

## Commands (all reads, gate rows V78)
| Use | Command |
|---|---|
| session | `terminal pager 0` (this login's pager only; never saved) |
| every run | `show curpriv`, `show version` |
| inventory | `show ip address`, `show interface ip brief`, `show route`, `show failover \| include This host`, `show mode` |
| backup | `more system:running-config`, `show startup-config`, `show version`, `show mode` |

## What is stored
- **Confirm:** hostname (the "<name> up …" line), model (`Hardware:`), software version. Not an ASA → failed.
- **Inventory:** one context named after the hostname: interfaces (address/prefix from `show ip address`, up/down from
  `show interface ip brief`, sub-interfaces with their parent), routes (connected, local, static, default, OSPF, BGP,
  RIP with next hop and interface). Failover role and context mode are logged; persisting them is next.
- **Backup:** a gzip tar in the encrypted artefact store: `manifest.txt`, `running-config.txt` (with keys, as Backbox
  takes it), `startup-config.txt`, `show-version.txt`, `show-mode.txt`. Refused unless the running configuration has
  its `ASA Version` line.

## Backup: both, in order (PO decision 2026-09-25)
1. Configuration text on the shell: `more system:running-config`, `show startup-config`, `show version`, `show mode`.
   Required — without the running configuration's `ASA Version` line nothing is stored.
2. The ASA's own archive, on the same shell: `backup /noconfirm location disk0:/nexus-<job hex>.tar.gz` (identity
   certificates, WebVPN data, AnyConnect images and profiles; "Backup finished!" required; items the ASA reports as
   "Failed!" are listed in the manifest).
3. A second SSH session pulls it with the SCP protocol (`scp -f disk0:/…`) into a temporary file; a torn transfer
   never reaches the bundle. neXus never runs `ssh scopy enable`.
4. A third session deletes exactly that file (`delete /noconfirm disk0:/nexus-<job hex>.tar.gz`; the name is
   neXus's own, checked against `nexus-<hex>.tar.gz`), whether or not the pull worked.
5. One encrypted bundle: the text files, `asa-backup.tar.gz`, `manifest.txt`.

Outcomes: all parts → completed. Archive failed → the text is stored, the manifest says `MISSING: <why>`, and the job
fails as `partial: stored without ASA archive (…)` — visible, completed later by a new backup. Delete failed →
`cleanup_failed`, and the device leaves the backup schedule until cleared (the existing BK-7 rule).
Backbox reference: trail 34409263 (2026-09-22) runs the same text reads and the same archive/SCP step.

## Not yet
- Multi-context: a login lands in one context; `changeto` into each context (and the system context) is not issued.
- Configuration plane (parsed `show running-config`) — the onboarding flow records it as not available.

## Real-environment measurement (to do)
Add one ASA with a privilege-15 credential; record: confirm facts present (yes/no), interface and route counts,
backup size and file list, total time. Compare the bundle's configuration line count with the Backbox archive's.
