# Fortinet — FortiGate over SSH, FortiManager over JSON-RPC

**Status:** FROZEN for implementation (PO 2026-09-25: "Fortimanager ve GW'ler"; "Sen metodları hazırla, eklemeyi ben
yaparım"; one fwadm account everywhere via LDAP/RADIUS). Commands gated in V80. Delivery status IMPLEMENTED until the
first real-environment run below.

## FortiGate (vendor `fortinet`, role gateway, SSH port 22)
One interactive shell per run. Reads only. Context moves (`config global`, `config vdom`, `edit <vdom>`, `end`) change
no setting; VDOM names come from the device and are checked against `[A-Za-z0-9_.-]{1,31}` before use.
**Paging:** answered in the session (`--More--` → space). Backbox instead runs `config system console` /
`set output standard`, which is a persistent configuration change; neXus does not.

| Use | Command |
|---|---|
| every run | `get system status` (at the top level; inside `config global` when refused there) |
| inventory | `config global` → `show system interface` → `end`; per VDOM `config vdom` → `edit <vdom>` → `get router info routing-table all` → `end` (single-VDOM boxes: the two reads directly) |
| backup | `show` at the top level — the whole configuration, every VDOM, headed `#config-version=` (the same read Backbox takes) |

Stored: confirm → hostname, model (`FortiGate-1101E`), version (`v7.0.12 build0523`); inventory → one context per
VDOM with its interfaces (configured address, type, VLAN, parent, configured up/down) and routes; backup → encrypted
tgz with `fortigate.conf`, `system-status.txt`, `manifest.txt`; refused without the `#config-version=` header.

## FortiManager (vendor `fortinet`, role management server, HTTPS 443)
JSON-RPC at `POST /jsonrpc`: `exec /sys/login/user` (password only in the body, never logged), `get /sys/status`,
`get /dvmdb/device` (name, address, model, FortiOS version, connection, HA), `exec /sys/logout`. Confirm → hostname,
platform, version; inventory → the managed FortiGates, shown under "Managed devices" like the Management Center.
**Not yet:** FortiManager's own backup. Its CLI pushes `execute backup all-settings` to a server; the HOST-A SFTP
receiver used for the Radware Cyber Controller fits, and is the next step after the first confirm/inventory run.

## Real-environment measurement (to do, after the PO adds the devices)
One FortiGate with VDOMs and one FortiManager, fwadm credential: confirm facts present; VDOM, interface and route
counts; backup size and time; that `--More--` never timed a read out; that the FortiManager accepted the JSON-RPC
login with the basic-auth header the HTTPS client also sends.
