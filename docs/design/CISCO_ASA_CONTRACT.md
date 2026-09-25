# Cisco ASA — confirm, inventory, backup over SSH

**Status:** DRAFT until the first real-environment run (implementation authority: PO 2026-09-25 "Cisco ASA'ları
halledelim"; commands gated in V78). Freeze after the measurement below.

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

## Backbox comparison (trail 34409263, 2026-09-22, one ASA 9.22 on Firepower 4100, 63 s)
Backbox reads the same `more system:running-config`, `show startup-config`, `show version`, and additionally runs the
ASA's own `backup /noconfirm location disk0:…` and pulls the archive with SCP. That archive adds identity
certificates, WebVPN data and AnyConnect images/profiles, but it **writes a file to the device's flash** and needs
`ssh scopy enable` on the ASA. neXus does not do that step. **PO decision open:** keep the text backup, or add the
archive step as a gated class-1 write with removal of the file after the pull.

## Not yet
- Multi-context: a login lands in one context; `changeto` into each context (and the system context) is not issued.
- Configuration plane (parsed `show running-config`) — the onboarding flow records it as not available.

## Real-environment measurement (to do)
Add one ASA with a privilege-15 credential; record: confirm facts present (yes/no), interface and route counts,
backup size and file list, total time. Compare the bundle's configuration line count with the Backbox archive's.
